package com.bitchat.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

sealed class MeshBatteryState {
    abstract val level: Int

    data class Normal(override val level: Int) : MeshBatteryState()

    /** Non-mesh features throttled; mesh stays full. */
    data class Reduced(override val level: Int) : MeshBatteryState()

    /** Only the mesh runs; everything else stopped. Offload begun. */
    data class Minimal(override val level: Int) : MeshBatteryState()

    /**
     * Emergency offload in progress:
     * BLE at maximum power, queue being pushed to neighbours.
     */
    data class Critical(
        override val level: Int,
        val offloadComplete: Boolean = false
    ) : MeshBatteryState()

    /**
     * <5 % — farewell packet sent, device about to die.
     * Mesh should route around this node.
     */
    data class Dying(override val level: Int) : MeshBatteryState()
}

enum class BleAdvertisingMode { FULL, MAXIMUM, LOW_POWER, MINIMUM }

// ---------------------------------------------------------------------------
// Policy interface — implemented by MeshForegroundService (or a test double)
// ---------------------------------------------------------------------------

interface MeshBatteryPolicy {
    /** Switch BLE advertising + scan duty cycle. */
    fun setBleMode(mode: BleAdvertisingMode)

    /** Broadcast a LOW_BATTERY mesh packet so peers re-route proactively. */
    suspend fun broadcastLowBatteryAlert(levelPct: Int)

    /**
     * Push all queued store-and-forward packets to every reachable peer.
     * Should suspend until the queue is empty or [timeoutMs] expires.
     */
    suspend fun flushStoreForwardQueue(timeoutMs: Long)

    /** Broadcast current routing table + bloom filter to neighbours. */
    suspend fun broadcastRoutingState()

    /**
     * Send a FAREWELL mesh packet so peers remove this node from their
     * routing tables immediately rather than waiting for a timeout.
     */
    suspend fun broadcastFarewellPacket()

    /** Stop the EAS audio monitoring foreground service. */
    fun stopEasMonitoring()

    /** Put telemetry into the given profile (or stop it if null). */
    fun setTelemetryProfile(profile: String?)   // "minimal" | null = off

    /** Disable Wi-Fi to reclaim ~50–150 mA. */
    fun disableWifi()

    /** Dim screen (0–255) or null = restore auto. */
    fun setScreenBrightness(value: Int?)

    /** Set screen-off timeout in ms, or null = restore default. */
    fun setScreenTimeout(ms: Int?)
}

// ---------------------------------------------------------------------------
// Coordinator
// ---------------------------------------------------------------------------

/**
 * MeshBatteryCoordinator
 *
 * A self-contained, reactive component that:
 *  1. Monitors battery level via a BroadcastReceiver.
 *  2. Runs a state machine (Normal → Reduced → Minimal → Critical → Dying).
 *  3. In Critical, runs an async emergency offload sequence — pushing all
 *     stored packets and routing state to neighbouring peers before the
 *     device dies, while maximising BLE advertising power.
 *  4. In Dying, sends a farewell mesh packet so peers re-route immediately.
 *
 * The coordinator is intentionally decoupled from concrete service classes
 * via [MeshBatteryPolicy], making it testable and reusable.
 *
 * Usage:
 *   val coordinator = MeshBatteryCoordinator(context, policy)
 *   coordinator.start()
 *   // observe: coordinator.state.collect { ... }
 *   // cleanup: coordinator.stop()
 */
class MeshBatteryCoordinator(
    private val context: Context,
    private val policy: MeshBatteryPolicy,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "MeshBatteryCoordinator"

        // Thresholds (inclusive lower bound)
        const val THRESHOLD_REDUCED  = 50
        const val THRESHOLD_MINIMAL  = 20
        const val THRESHOLD_CRITICAL = 10
        const val THRESHOLD_DYING    = 5

        // How long to attempt the store-forward flush before giving up
        private const val OFFLOAD_TIMEOUT_MS     = 20_000L
        // Grace window after offload before we drop to minimum BLE
        private const val POST_OFFLOAD_GRACE_MS  = 3_000L
        // Minimum gap between re-triggering the offload sequence (avoid loops)
        private const val OFFLOAD_COOLDOWN_MS    = 60_000L
    }

    // Public reactive state
    private val _state = MutableStateFlow<MeshBatteryState>(MeshBatteryState.Normal(100))
    val state: StateFlow<MeshBatteryState> = _state.asStateFlow()

    private var batteryReceiver: BroadcastReceiver? = null
    private var offloadJob: Job? = null
    private var lastOffloadTime = 0L

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    fun start() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val raw   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (raw < 0) return
                val pct = raw * 100 / scale
                onBatteryLevel(pct)
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Log.d(TAG, "Started")
    }

    fun stop() {
        batteryReceiver?.let { context.unregisterReceiver(it) }
        batteryReceiver = null
        offloadJob?.cancel()
        scope.cancel()
        Log.d(TAG, "Stopped")
    }

    // ---------------------------------------------------------------------------
    // State machine
    // ---------------------------------------------------------------------------

    private fun onBatteryLevel(pct: Int) {
        val next = stateFor(pct)
        val current = _state.value

        if (next::class == current::class) return   // same state, no transition

        Log.i(TAG, "Battery $pct% → ${next::class.simpleName}")
        _state.value = next
        applyTransition(previous = current, next = next)
    }

    private fun stateFor(pct: Int): MeshBatteryState = when {
        pct <= THRESHOLD_DYING    -> MeshBatteryState.Dying(pct)
        pct <= THRESHOLD_CRITICAL -> MeshBatteryState.Critical(pct)
        pct <= THRESHOLD_MINIMAL  -> MeshBatteryState.Minimal(pct)
        pct <= THRESHOLD_REDUCED  -> MeshBatteryState.Reduced(pct)
        else                      -> MeshBatteryState.Normal(pct)
    }

    private fun applyTransition(previous: MeshBatteryState, next: MeshBatteryState) {
        when (next) {
            is MeshBatteryState.Normal   -> onEnterNormal()
            is MeshBatteryState.Reduced  -> onEnterReduced()
            is MeshBatteryState.Minimal  -> onEnterMinimal()
            is MeshBatteryState.Critical -> onEnterCritical(next.level)
            is MeshBatteryState.Dying    -> onEnterDying(next.level)
        }
    }

    // ---------------------------------------------------------------------------
    // Transition handlers
    // ---------------------------------------------------------------------------

    private fun onEnterNormal() {
        policy.setBleMode(BleAdvertisingMode.FULL)
        policy.setTelemetryProfile("navigation")   // or whatever the user had
        policy.setScreenBrightness(null)            // restore auto
        policy.setScreenTimeout(null)               // restore default
        Log.d(TAG, "Normal: all features restored")
    }

    private fun onEnterReduced() {
        // Mesh untouched. Shed non-critical load.
        policy.setTelemetryProfile("minimal")
        policy.setScreenBrightness(150)
        Log.d(TAG, "Reduced: telemetry minimal, screen dimmed")
    }

    private fun onEnterMinimal() {
        // Only the mesh runs. Stop everything else.
        policy.stopEasMonitoring()
        policy.setTelemetryProfile(null)            // off
        policy.disableWifi()
        policy.setScreenBrightness(30)
        policy.setScreenTimeout(15_000)
        // Mesh stays FULL — do not reduce BLE here
        Log.d(TAG, "Minimal: EAS/telemetry/Wi-Fi off, mesh full")
    }

    private fun onEnterCritical(level: Int) {
        val now = System.currentTimeMillis()
        if (now - lastOffloadTime < OFFLOAD_COOLDOWN_MS) {
            Log.d(TAG, "Critical: offload cooldown active, skipping re-trigger")
            return
        }
        lastOffloadTime = now

        offloadJob?.cancel()
        offloadJob = scope.launch {
            runEmergencyOffload(level)
        }
    }

    private fun onEnterDying(level: Int) {
        offloadJob?.cancel()
        scope.launch {
            runFarewellSequence(level)
        }
    }

    // ---------------------------------------------------------------------------
    // Emergency offload sequence (runs async in Critical state)
    //
    // Priority order:
    //   1. Alert peers so they start re-routing NOW (before offload completes).
    //   2. Maximise BLE so neighbours can receive everything.
    //   3. Flush the store-forward queue — this is the critical payload.
    //   4. Push routing state so the mesh keeps working after we die.
    //   5. Brief grace window, then drop to low-power to squeeze last minutes.
    // ---------------------------------------------------------------------------

    private suspend fun runEmergencyOffload(level: Int) {
        Log.i(TAG, "Emergency offload started at $level%")

        // Step 1 — alert mesh peers immediately
        try { policy.broadcastLowBatteryAlert(level) }
        catch (e: Exception) { Log.e(TAG, "broadcastLowBatteryAlert failed", e) }

        // Step 2 — maximise BLE so neighbours can absorb our queue
        policy.setBleMode(BleAdvertisingMode.MAXIMUM)

        // Step 3 — flush the store-forward queue within the time budget
        try {
            policy.flushStoreForwardQueue(OFFLOAD_TIMEOUT_MS)
            Log.i(TAG, "Store-forward queue flushed")
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Offload timed out — queue may not be fully delivered")
        } catch (e: Exception) {
            Log.e(TAG, "flushStoreForwardQueue failed", e)
        }

        // Step 4 — push routing knowledge so mesh heals itself
        try { policy.broadcastRoutingState() }
        catch (e: Exception) { Log.e(TAG, "broadcastRoutingState failed", e) }

        // Step 5 — update state to reflect offload complete
        _state.value = MeshBatteryState.Critical(level, offloadComplete = true)
        Log.i(TAG, "Offload complete — entering grace window")

        // Step 6 — grace window: stay at maximum BLE so any peer
        // that missed earlier packets can still pull from us
        delay(POST_OFFLOAD_GRACE_MS)

        // Step 7 — drop to low power; we've done all we can
        policy.setBleMode(BleAdvertisingMode.LOW_POWER)
        policy.setScreenBrightness(10)
        policy.setScreenTimeout(10_000)
        Log.i(TAG, "Emergency offload sequence complete")
    }

    // ---------------------------------------------------------------------------
    // Farewell sequence (runs in Dying state, <5%)
    // ---------------------------------------------------------------------------

    private suspend fun runFarewellSequence(level: Int) {
        Log.i(TAG, "Farewell sequence at $level%")

        // One last boost so the farewell packet reaches as many peers as possible
        policy.setBleMode(BleAdvertisingMode.MAXIMUM)

        try { policy.broadcastFarewellPacket() }
        catch (e: Exception) { Log.e(TAG, "broadcastFarewellPacket failed", e) }

        delay(500) // give the packet time to propagate

        // Drop to absolute minimum — preserve whatever milliamps remain
        policy.setBleMode(BleAdvertisingMode.MINIMUM)
        policy.setScreenBrightness(0)
        policy.setScreenTimeout(5_000)
        Log.i(TAG, "Farewell complete — device in minimum-power mode")
    }

    // ---------------------------------------------------------------------------
    // Introspection
    // ---------------------------------------------------------------------------

    fun currentLevel(): Int = _state.value.level

    fun summary(): String = buildString {
        val s = _state.value
        appendLine("MeshBatteryCoordinator")
        appendLine("  State : ${s::class.simpleName}")
        appendLine("  Level : ${s.level}%")
        if (s is MeshBatteryState.Critical) {
            appendLine("  Offload complete: ${s.offloadComplete}")
        }
        appendLine("  Thresholds: normal>$THRESHOLD_REDUCED% / reduced>$THRESHOLD_MINIMAL% / minimal>$THRESHOLD_CRITICAL% / critical>$THRESHOLD_DYING%")
    }
}
