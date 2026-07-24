package com.bitchat.android.telemetry

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.telemetry.sensors.AccelerationSensor
import com.bitchat.android.telemetry.sensors.FallEvent
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject
import java.util.Date

/**
 * AI-triggerable telemetry interface.
 *
 * Provides a programmatic API for on-device AI agents to:
 * - Enable/disable sensors based on situational awareness
 * - Read sensor data for decision-making
 * - Pack telemetry for mesh transmission
 * - Optimize sensor polling rates for battery efficiency
 */
class TelemetryAgent private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryAgent"

        @Volatile
        private var instance: TelemetryAgent? = null

        fun getInstance(context: Context): TelemetryAgent {
            return instance ?: synchronized(this) {
                instance ?: TelemetryAgent(context.applicationContext).also { instance = it }
            }
        }

        /** No significant motion for this long → dead-man alert. */
        private const val DEADMAN_THRESHOLD_MS = 3 * 60 * 1000L   // 3 minutes
        private const val DEADMAN_CHECK_MS     = 60_000L           // check every 60 s
    }

    private var telemeter: Telemeter? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Injected after app init — set via attachMesh()
    private var meshService: BluetoothMeshService? = null

    private var deadmanJob: kotlinx.coroutines.Job? = null
    private var lastDeadmanAlertAt = 0L
    /** Callback that injects a BitchatMessage into the local chat feed (set by the ViewModel). */
    var onLocalAlert: ((BitchatMessage) -> Unit)? = null

    fun attachMesh(service: BluetoothMeshService) {
        meshService = service
        Log.d(TAG, "Mesh service attached")
    }

    fun detachMesh() {
        meshService = null
    }

    private val _activeSensors = MutableStateFlow<Set<String>>(emptySet())
    val activeSensors: StateFlow<Set<String>> = _activeSensors.asStateFlow()

    private val _lastReading = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val lastReading: StateFlow<Map<String, Any?>> = _lastReading.asStateFlow()

    data class SensorProfile(
        val sensors: Set<String>,
        val pollIntervalMs: Long = 30_000L
    )

    val PROFILE_SURVIVAL = SensorProfile(
        sensors = setOf("time", "battery", "location", "pressure", "temperature", "connectivity"),
        pollIntervalMs = 60_000L
    )

    val PROFILE_NAVIGATION = SensorProfile(
        sensors = setOf("time", "location", "acceleration", "magnetic_field", "gravity", "pressure", "connectivity"),
        pollIntervalMs = 10_000L
    )

    val PROFILE_SEISMIC = SensorProfile(
        sensors = setOf("time", "acceleration", "gravity", "angular_velocity"),
        pollIntervalMs = 1_000L
    )

    val PROFILE_ENVIRONMENT = SensorProfile(
        sensors = setOf("time", "temperature", "humidity", "pressure", "ambient_light"),
        pollIntervalMs = 30_000L
    )

    val PROFILE_MINIMAL = SensorProfile(
        sensors = setOf("time", "battery"),
        pollIntervalMs = 120_000L
    )

    private fun getOrCreate(): Telemeter {
        return telemeter ?: Telemeter(context).also { telemeter = it }
    }

    fun enableSensor(name: String) {
        getOrCreate().enable(name)
        _activeSensors.value = _activeSensors.value + name
        Log.d(TAG, "Enabled sensor: $name")
    }

    fun disableSensor(name: String) {
        getOrCreate().disable(name)
        _activeSensors.value = _activeSensors.value - name
        Log.d(TAG, "Disabled sensor: $name")
    }

    fun applyProfile(profile: SensorProfile) {
        val t = getOrCreate()
        val current = _activeSensors.value
        (current - profile.sensors).forEach { t.disable(it) }
        profile.sensors.forEach { t.enable(it) }
        _activeSensors.value = profile.sensors
        Log.d(TAG, "Applied profile with ${profile.sensors.size} sensors, poll=${profile.pollIntervalMs}ms")
    }

    fun readAll(): Map<String, Any?> {
        val readings = getOrCreate().readAll()
        _lastReading.value = readings
        return readings
    }

    fun read(sensor: String): Any? {
        return getOrCreate().read(sensor)
    }

    fun pack(): ByteArray {
        return getOrCreate().packed()
    }

    fun render(): List<Map<String, Any>> {
        return getOrCreate().render()
    }

    /**
     * Returns a structured JSON-friendly summary for AI consumption.
     */
    fun getSummary(): Map<String, Any> {
        val t = getOrCreate()
        val readings = t.readAll()
        val packed = t.packed()
        return mapOf(
            "active_sensors" to _activeSensors.value.toList(),
            "sensor_count" to _activeSensors.value.size,
            "packed_bytes" to packed.size,
            "readings" to readings.mapValues { (_, v) -> v?.toString() ?: "null" },
            "timestamp" to System.currentTimeMillis()
        )
    }

    /**
     * AI decision helper: suggests a profile based on battery and context.
     */
    fun suggestProfile(batteryPercent: Int, isMoving: Boolean, isEmergency: Boolean): SensorProfile {
        return when {
            isEmergency -> PROFILE_NAVIGATION
            batteryPercent < 15 -> PROFILE_MINIMAL
            batteryPercent < 30 -> PROFILE_SURVIVAL
            isMoving -> PROFILE_NAVIGATION
            else -> PROFILE_ENVIRONMENT
        }
    }

    /**
     * Broadcast telemetry as a JSON message on the main public channel.
     * Call this periodically or on demand (e.g. every 30s in survival mode).
     */
    fun broadcastToMainChannel(channel: String? = null) {
        val mesh = meshService ?: run {
            Log.w(TAG, "broadcastToMainChannel: mesh not attached")
            return
        }
        val json = buildTelemetryJson()
        mesh.sendMessage("[TELEMETRY] $json", channel = channel)
        Log.d(TAG, "Broadcast telemetry to channel=${channel ?: "main"}: ${json.length} chars")

        // Also echo into the local chat feed so the sender sees their own broadcast
        // rendered the same way peers see it via MessageHandler.handleAnnounce.
        val packed = try { pack() } catch (_: Exception) { null }
        val line = packed?.let { com.bitchat.android.mesh.TelemetryFormatter.format(it) }
            ?: "telemetry broadcast (${json.length} chars)"
        onLocalAlert?.invoke(
            BitchatMessage(
                sender = "me",
                content = line,
                timestamp = Date(),
                isRelay = false
            )
        )
    }

    /**
     * Send telemetry as a private message to every currently connected peer.
     * Uses the peer nickname stored in BluetoothMeshService.
     */
    fun sendToAllPeers() {
        val mesh = meshService ?: run {
            Log.w(TAG, "sendToAllPeers: mesh not attached")
            return
        }
        val json = buildTelemetryJson()
        val peers = mesh.getConnectedPeers()
        if (peers.isEmpty()) {
            Log.d(TAG, "sendToAllPeers: no peers connected")
            return
        }
        val nicknames = mesh.getPeerNicknames()
        peers.forEach { peerID ->
            val nickname = nicknames[peerID] ?: peerID
            mesh.sendPrivateMessage("[TELEMETRY] $json", peerID, nickname)
        }
        Log.d(TAG, "Sent telemetry to ${peers.size} peer(s)")
    }

    private fun buildTelemetryJson(): String {
        return try {
            val readings = getOrCreate().readAll()
            val obj = JSONObject()
            obj.put("ts", System.currentTimeMillis() / 1000)
            readings.forEach { (k, v) -> obj.put(k, v?.toString() ?: JSONObject.NULL) }
            obj.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildTelemetryJson failed", e)
            "{\"error\":\"pack_failed\"}"
        }
    }

    /**
     * Start observing the AccelerationSensor's fall events and the dead-man timer.
     * Call once after [applyProfile] has enabled the "acceleration" sensor.
     */
    fun startFallMonitoring() {
        // Observe fall events from the AccelerationSensor
        scope.launch {
            val accel = getOrCreate().getSensor("acceleration") as? AccelerationSensor ?: return@launch
            accel.fallEvents.filterNotNull().collect { event ->
                dispatchFallAlert(event)
            }
        }

        // Dead-man timer: check every 60 s whether significant motion has been seen
        deadmanJob?.cancel()
        deadmanJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(DEADMAN_CHECK_MS)
                val accel = getOrCreate().getSensor("acceleration") as? AccelerationSensor
                if (accel != null) {
                    val silentMs = System.currentTimeMillis() - accel.lastMotionAt
                    if (silentMs >= DEADMAN_THRESHOLD_MS) {
                        val now = System.currentTimeMillis()
                        if (now - lastDeadmanAlertAt >= DEADMAN_THRESHOLD_MS) {
                            lastDeadmanAlertAt = now
                            dispatchDeadmanAlert(silentMs)
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Fall monitoring started")
    }

    fun stopFallMonitoring() {
        deadmanJob?.cancel()
        deadmanJob = null
    }

    private fun dispatchFallAlert(event: FallEvent) {
        val peakStr = "%.1f".format(event.peakImpactG)
        val msg = "ALERT: possible fall detected — peak ${peakStr}g, no movement ${event.stillnessDurationMs / 1000}s"
        Log.w(TAG, "FALL ALERT: $msg")
        broadcastAlert(msg)
    }

    private fun dispatchDeadmanAlert(silentMs: Long) {
        val minutes = silentMs / 60000
        val msg = "ALERT: no movement for $minutes min — person may need help"
        Log.w(TAG, "DEADMAN ALERT: $msg")
        broadcastAlert(msg)
    }

    private fun broadcastAlert(text: String) {
        // Inject into local chat feed for the owner's own screen
        onLocalAlert?.invoke(
            BitchatMessage(
                sender    = "system",
                content   = text,
                timestamp = Date(),
                isRelay   = false
            )
        )
        // Also broadcast over the mesh so neighbors see it
        meshService?.broadcastTelemetry(
            ("[ALERT] $text").toByteArray(Charsets.UTF_8)
        )
    }

    fun release() {
        stopFallMonitoring()
        telemeter?.release()
        telemeter = null
        scope.cancel()
        _activeSensors.value = emptySet()
        _lastReading.value = emptyMap()
        instance = null
    }
}
