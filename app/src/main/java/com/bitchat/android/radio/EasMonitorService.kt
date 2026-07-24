package com.bitchat.android.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitchat.android.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EAS Monitor Service
 *
 * Foreground service that passively listens to FM audio via AudioSource.FM_TUNER
 * and detects the SAME (Specific Area Message Encoding) / EAS attention signal:
 * dual tones at 853 Hz + 960 Hz simultaneously.
 *
 * Auto-shuts off after 15 minutes to conserve battery. The UI can restart it.
 *
 * Alert state is broadcast via [alertState] StateFlow — observe from ViewModel.
 */
class EasMonitorService : Service() {

    companion object {
        private const val TAG = "EasMonitorService"

        private const val NOTIFICATION_CHANNEL_ID = "eas_monitor"
        private const val NOTIFICATION_ID = 9001

        /** Auto-shutoff after 15 minutes */
        private const val MAX_RUNTIME_MS = 15 * 60 * 1000L

        /** Audio config */
        private const val SAMPLE_RATE_HZ = 8000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_SAMPLES = 512

        // Shared observable state — survives service restarts within process
        private val _alertState = MutableStateFlow<EasAlertState>(EasAlertState.Monitoring)
        val alertState: StateFlow<EasAlertState> = _alertState.asStateFlow()

        fun clearAlert() {
            _alertState.value = EasAlertState.Monitoring
        }

        sealed class EasAlertState {
            object Monitoring : EasAlertState()
            data class AlertDetected(
                val detectedAt: Long = System.currentTimeMillis(),
                val stationName: String = "Unknown",
                val frequencyMHz: Float = 0f
            ) : EasAlertState()
            object Stopped : EasAlertState()
        }

        /** Call this to start monitoring */
        fun start(context: Context, stationName: String, frequencyMHz: Float) {
            currentStation = stationName
            currentFrequency = frequencyMHz
            _alertState.value = EasAlertState.Monitoring
            val intent = Intent(context, EasMonitorService::class.java)
            context.startForegroundService(intent)
        }

        /** Call this to stop monitoring */
        fun stop(context: Context) {
            val intent = Intent(context, EasMonitorService::class.java)
            context.stopService(intent)
        }

        // Station info passed to service at start time
        @Volatile var currentStation: String = "Unknown"
        @Volatile var currentFrequency: Float = 0f
    }

    private var audioRecord: AudioRecord? = null
    private var monitorThread: Thread? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private val detector = GoertzelToneDetector(SAMPLE_RATE_HZ)

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "EAS monitor service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startMonitoring()
            scheduleAutoShutoff()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        _alertState.value = EasAlertState.Stopped
        Log.d(TAG, "EAS monitor service destroyed")
        super.onDestroy()
    }

    // ── Audio monitoring ──────────────────────────────────────────────────────

    private fun startMonitoring() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, CHUNK_SIZE_SAMPLES * 2)

        // AudioSource.FM_TUNER = 1998 is @hide/@SystemApi — use the constant directly.
        // Falls back to DEFAULT if the device doesn't expose FM_TUNER as a capture source.
        val FM_TUNER_SOURCE = 1998
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord(
                FM_TUNER_SOURCE,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also { ar ->
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    ar.release()
                    throw IllegalStateException("FM_TUNER source not initialized")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FM_TUNER audio source not available, trying DEFAULT: ${e.message}")
            try {
                @Suppress("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create AudioRecord: ${e2.message}")
                stopSelf()
                return
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            stopSelf()
            return
        }

        audioRecord = record
        detector.reset()
        isRunning = true

        record.startRecording()

        monitorThread = Thread {
            Log.d(TAG, "EAS monitoring started")
            val buffer = ShortArray(CHUNK_SIZE_SAMPLES)

            while (isRunning) {
                val read = record.read(buffer, 0, CHUNK_SIZE_SAMPLES)
                if (read <= 0) continue

                val samples = if (read < CHUNK_SIZE_SAMPLES) buffer.copyOf(read) else buffer
                val result = detector.detect(samples)

                if (result.isEasAlert) {
                    Log.w(TAG, "EAS alert detected! 853Hz=${result.magnitude853}, 960Hz=${result.magnitude960}")
                    val alert = EasAlertState.AlertDetected(
                        detectedAt = System.currentTimeMillis(),
                        stationName = currentStation,
                        frequencyMHz = currentFrequency
                    )
                    handler.post { _alertState.value = alert }
                    updateNotificationForAlert()
                    // Don't stop — continue monitoring for additional alerts
                }
            }

            record.stop()
            record.release()
            Log.d(TAG, "EAS monitoring stopped")
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopMonitoring() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        monitorThread?.interrupt()
        monitorThread = null
        audioRecord = null
    }

    private fun scheduleAutoShutoff() {
        handler.postDelayed({
            Log.d(TAG, "EAS monitor auto-shutoff after 15 minutes")
            stopSelf()
        }, MAX_RUNTIME_MS)
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Emergency Alert Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors FM audio for emergency broadcast signals"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EAS Monitor Active")
            .setContentText("Monitoring ${currentStation} for emergency alerts")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationForAlert() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EMERGENCY ALERT DETECTED")
            .setContentText("EAS signal on ${currentStation} ${currentFrequency} MHz")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
