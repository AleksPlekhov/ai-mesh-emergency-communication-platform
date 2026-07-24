package com.bitchat.android.radio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.radio.jni.FmNativeWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * FM Hardware Controller
 *
 * 4-layer progressive enhancement for FM tuning. Tries each layer in order,
 * falls back gracefully so the screen is always useful regardless of device:
 *
 *   Layer 0 — JNI (libqcomfmjni): Qualcomm rooted/system devices only
 *   Layer 1 — RadioManager (reflection): android.hardware.radio is @SystemApi;
 *             accessible at runtime on OEM devices via reflection even though
 *             it isn't in the public SDK stubs.
 *   Layer 2 — OEM Intent: android.intent.action.FM to open OEM FM app
 *   Layer 3 — Manual: Display frequency, COPY button, user tunes manually
 *
 * All blocking work runs on Dispatchers.IO.
 *
 * Lifecycle cleanup: call release() from a DisposableEffect or ViewModel onCleared().
 * Optionally wire to a LifecycleOwner via attachLifecycle() to auto-release on Stop.
 */
class FmHardwareController(private val context: Context) {

    companion object {
        private const val TAG = "FmHardwareController"
        private const val OEM_FM_INTENT = "android.intent.action.FM"

        // android.hardware.radio is @SystemApi — access via reflection at runtime
        private const val RADIO_MANAGER_CLASS = "android.hardware.radio.RadioManager"
        private const val RADIO_TUNER_CLASS   = "android.hardware.radio.RadioTuner"
        private const val BAND_FM    = 1
        private const val BAND_FM_HD = 4
    }

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class FmState {
        object Idle : FmState()
        object Connecting : FmState()
        data class Tuned(
            val station: EmergencyFmStation,
            val layer: TuneLayer,
            val signalStrengthDbm: Int = -60,
            val isMuted: Boolean = false,
            val locationSource: EmergencyFmRepository.LocationSource = EmergencyFmRepository.LocationSource.GPS_FRESH
        ) : FmState()
        data class AppLaunched(val station: EmergencyFmStation) : FmState()
        data class ManualInstruction(val station: EmergencyFmStation) : FmState()
        data class Error(val message: String) : FmState()
    }

    enum class TuneLayer {
        JNI,           // Layer 0 — native Qualcomm HAL
        RADIO_MANAGER, // Layer 1 — android.hardware.radio (reflection)
        OEM_APP,       // Layer 2 — android.intent.action.FM intent
        MANUAL         // Layer 3 — user tunes manually
    }

    private val _state = MutableStateFlow<FmState>(FmState.Idle)
    val state: StateFlow<FmState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reflected RadioTuner instance — Any to avoid compile-time binding
    private var radioTuner: Any? = null
    private var activeLayer: TuneLayer? = null

    // ── Optional lifecycle auto-release ───────────────────────────────────────

    /**
     * Attach to a LifecycleOwner so FM resources are released when the owner stops.
     * Optional — the Compose DisposableEffect in EmergencyFmScreen also handles cleanup.
     */
    fun attachLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (activeLayer == TuneLayer.JNI || activeLayer == TuneLayer.RADIO_MANAGER) {
                    Log.d(TAG, "Lifecycle stopped — releasing FM resources")
                    release()
                }
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun tune(station: EmergencyFmStation) {
        scope.launch {
            _state.value = FmState.Connecting

            if (tryJni(station)) return@launch
            if (tryRadioManager(station)) return@launch
            if (tryOemIntent(station)) return@launch

            _state.value = FmState.ManualInstruction(station)
            activeLayer = TuneLayer.MANUAL
            Log.d(TAG, "Layer 3 (manual): ${station.frequencyMHz} MHz")
        }
    }

    fun setMuted(muted: Boolean) {
        val current = _state.value as? FmState.Tuned ?: return
        scope.launch {
            when (activeLayer) {
                TuneLayer.JNI -> {
                    if (FmNativeWrapper.isAvailable()) FmNativeWrapper.setMute(muted)
                }
                TuneLayer.RADIO_MANAGER -> {
                    try {
                        val tuner = radioTuner ?: return@launch
                        val m: Method = tuner.javaClass.getMethod("setMuted", Boolean::class.java)
                        m.invoke(tuner, muted)
                    } catch (e: Exception) {
                        Log.w(TAG, "setMuted reflection failed: ${e.message}")
                    }
                }
                else -> return@launch
            }
            _state.value = current.copy(isMuted = muted)
        }
    }

    fun release() {
        scope.launch {
            when (activeLayer) {
                TuneLayer.JNI -> {
                    if (FmNativeWrapper.isAvailable()) {
                        runCatching { FmNativeWrapper.setMute(true) }
                        runCatching { FmNativeWrapper.powerDown(0) }
                        runCatching { FmNativeWrapper.closeDev() }
                    }
                }
                TuneLayer.RADIO_MANAGER -> {
                    try {
                        val tuner = radioTuner ?: return@launch
                        runCatching { tuner.javaClass.getMethod("cancel").invoke(tuner) }
                        runCatching { tuner.javaClass.getMethod("close").invoke(tuner) }
                        radioTuner = null
                    } catch (e: Exception) {
                        Log.w(TAG, "release reflection failed: ${e.message}")
                    }
                }
                else -> { /* OEM app / manual — nothing to release */ }
            }
            activeLayer = null
            _state.value = FmState.Idle
            Log.d(TAG, "FM released")
        }
    }

    // ── Layer 0: Qualcomm JNI ─────────────────────────────────────────────────

    private fun tryJni(station: EmergencyFmStation): Boolean {
        if (!FmNativeWrapper.loadNative()) return false
        return try {
            val fd = FmNativeWrapper.openDev()
            if (fd < 0) return false
            val pw = FmNativeWrapper.powerUp(station.frequencyMHz)
            if (pw != 0) { FmNativeWrapper.closeDev(); return false }
            activeLayer = TuneLayer.JNI
            _state.value = FmState.Tuned(station, TuneLayer.JNI)
            Log.d(TAG, "Layer 0 (JNI): ${station.frequencyMHz} MHz")
            true
        } catch (e: Exception) {
            Log.w(TAG, "JNI layer: ${e.message}")
            false
        }
    }

    // ── Layer 1: RadioManager via reflection (@SystemApi, runtime only) ────────

    @SuppressLint("NewApi")
    private fun tryRadioManager(station: EmergencyFmStation): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            // Resolve android.hardware.radio.RadioManager at runtime
            val rmClass = Class.forName(RADIO_MANAGER_CLASS)

            // Context.getSystemService("radio") — RADIO_SERVICE = "radio"
            val radioManager = context.getSystemService("radio") ?: run {
                Log.d(TAG, "RadioManager service not available")
                return false
            }

            // listModules() → List<ModuleProperties>
            val listModulesMethod = rmClass.getMethod("listModules")
            @Suppress("UNCHECKED_CAST")
            val modules = listModulesMethod.invoke(radioManager) as? List<*>
            if (modules.isNullOrEmpty()) {
                Log.d(TAG, "No radio modules")
                return false
            }

            // Find FM module — ModuleProperties.getBands() → BandDescriptor[]
            // BandDescriptor.getType() → int (BAND_FM=1, BAND_FM_HD=4)
            var targetModule: Any? = null
            var targetBand: Any? = null
            outer@ for (module in modules) {
                module ?: continue
                val getBands = module.javaClass.getMethod("getBands")
                val bands = getBands.invoke(module) as? Array<*> ?: continue
                for (band in bands) {
                    band ?: continue
                    val type = band.javaClass.getMethod("getType").invoke(band) as? Int ?: continue
                    if (type == BAND_FM || type == BAND_FM_HD) {
                        targetModule = module
                        targetBand = band
                        break@outer
                    }
                }
            }

            if (targetModule == null || targetBand == null) {
                Log.d(TAG, "No FM band module found")
                return false
            }

            val moduleId = targetModule.javaClass.getMethod("getId").invoke(targetModule) as? Int ?: return false
            val freqHz = (station.frequencyMHz * 1_000_000).toInt()

            // openTuner(int moduleId, BandDescriptor config, boolean withAudio, Callback callback, Handler handler)
            val openTuner = rmClass.getMethod(
                "openTuner", Int::class.java, Class.forName("$RADIO_MANAGER_CLASS\$BandDescriptor"),
                Boolean::class.java, Class.forName("$RADIO_TUNER_CLASS\$Callback"),
                android.os.Handler::class.java
            )
            // We pass null callback — acceptable for just tuning
            val tuner = openTuner.invoke(radioManager, moduleId, targetBand, true, null, null) ?: run {
                Log.d(TAG, "openTuner returned null — HAL not exposed")
                return false
            }

            // tune(int channel, int subChannel)
            tuner.javaClass.getMethod("tune", Int::class.java, Int::class.java).invoke(tuner, freqHz, 0)

            radioTuner = tuner
            activeLayer = TuneLayer.RADIO_MANAGER
            _state.value = FmState.Tuned(station, TuneLayer.RADIO_MANAGER)
            Log.d(TAG, "Layer 1 (RadioManager): ${station.frequencyMHz} MHz")
            true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "RadioManager class not found: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "RadioManager layer: ${e.message}")
            radioTuner?.let { t ->
                runCatching { t.javaClass.getMethod("cancel").invoke(t) }
            }
            radioTuner = null
            false
        }
    }

    // ── Layer 2: OEM FM intent ────────────────────────────────────────────────

    private fun tryOemIntent(station: EmergencyFmStation): Boolean {
        return try {
            val intent = Intent(OEM_FM_INTENT).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("frequency", station.frequencyMHz)
            }
            val canResolve = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (!canResolve) {
                Log.d(TAG, "No OEM FM app for $OEM_FM_INTENT")
                return false
            }
            context.startActivity(intent)
            activeLayer = TuneLayer.OEM_APP
            _state.value = FmState.AppLaunched(station)
            Log.d(TAG, "Layer 2 (OEM intent): ${station.frequencyMHz} MHz")
            true
        } catch (e: Exception) {
            Log.w(TAG, "OEM intent layer: ${e.message}")
            false
        }
    }
}
