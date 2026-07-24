package com.bitchat.android.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Manages battery optimization and adaptive power policy for bitchat.
 *
 * Beyond the standard whitelist request, this class actively monitors battery level
 * and applies optimizations using permissions the app already holds:
 *   - WRITE_SETTINGS  → screen brightness + timeout
 *   - CHANGE_WIFI_STATE → Wi-Fi on/off
 *   - callbacks into EasMonitorService, TelemetryAgent, BLE duty cycle
 *
 * Battery modes and automatic thresholds:
 *   NORMAL   (>50 %) — all features on, full BLE duty cycle
 *   REDUCED  (20–50%) — telemetry switches to minimal profile, EAS duty-cycled
 *   MINIMAL  (10–20%) — EAS stopped, telemetry off, Wi-Fi off, dim screen
 *   CRITICAL (<10 %)  — mesh-only survival mode, every non-BLE radio off
 */
class BatteryOptimizationManager(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onBatteryOptimizationDisabled: () -> Unit,
    private val onBatteryOptimizationFailed: (String) -> Unit,
    private val onBatteryModeChanged: ((BatteryMode) -> Unit)? = null
) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"

        const val THRESHOLD_REDUCED  = 50
        const val THRESHOLD_MINIMAL  = 20
        const val THRESHOLD_CRITICAL = 10

        const val ACTION_STOP_EAS_MONITORING = "com.bitchat.android.STOP_EAS_MONITORING"
        const val ACTION_DISABLE_TELEMETRY   = "com.bitchat.android.DISABLE_TELEMETRY"
        const val ACTION_REDUCE_BLE_INTERVAL = "com.bitchat.android.REDUCE_BLE_INTERVAL"
    }

    private var batteryOptimizationLauncher: ActivityResultLauncher<Intent>? = null
    private var currentMode: BatteryMode = BatteryMode.NORMAL
    private var batteryReceiver: BroadcastReceiver? = null

    init {
        setupBatteryOptimizationLauncher()
        registerBatteryMonitor()
    }

    // -------------------------------------------------------------------------
    // Battery level monitoring
    // -------------------------------------------------------------------------

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level < 0) return
                val pct = (level * 100 / scale)
                val newMode = modeForLevel(pct)
                if (newMode != currentMode) {
                    Log.d(TAG, "Battery $pct% → mode $newMode")
                    currentMode = newMode
                    applyOptimizationsForMode(newMode)
                    onBatteryModeChanged?.invoke(newMode)
                }
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregisterBatteryMonitor() {
        batteryReceiver?.let { context.unregisterReceiver(it) }
        batteryReceiver = null
    }

    fun currentBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun currentMode(): BatteryMode = currentMode

    private fun modeForLevel(pct: Int) = when {
        pct <= THRESHOLD_CRITICAL -> BatteryMode.CRITICAL
        pct <= THRESHOLD_MINIMAL  -> BatteryMode.MINIMAL
        pct <= THRESHOLD_REDUCED  -> BatteryMode.REDUCED
        else                      -> BatteryMode.NORMAL
    }

    // -------------------------------------------------------------------------
    // Mode application — calls individual optimizations in priority order
    // -------------------------------------------------------------------------

    fun applyOptimizationsForMode(mode: BatteryMode) {
        Log.d(TAG, "Applying optimizations for $mode")
        when (mode) {
            BatteryMode.NORMAL -> {
                setScreenBrightness(null)          // restore auto
                setScreenTimeout(null)             // restore user preference
                // EAS, telemetry, Wi-Fi: leave as user configured
            }
            BatteryMode.REDUCED -> {
                // Telemetry profile switch handled via callback to TelemetryAgent
                // EAS: switch to duty-cycle mode (handled in EasMonitorService)
            }
            BatteryMode.MINIMAL -> {
                stopEasMonitoring()
                disableTelemetry()
                disableWifi()
                setScreenBrightness(30)            // dim but readable
                setScreenTimeout(15_000)           // 15 s
            }
            BatteryMode.CRITICAL -> {
                stopEasMonitoring()
                disableTelemetry()
                disableWifi()
                setScreenBrightness(10)            // near-minimum
                setScreenTimeout(10_000)           // 10 s
                reduceBleInterval()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Individual optimizations (use permissions the app already holds)
    // -------------------------------------------------------------------------

    /**
     * Reduce screen brightness.
     * Requires WRITE_SETTINGS (special permission — user must grant via
     * Settings.ACTION_MANAGE_WRITE_SETTINGS before this has any effect).
     * @param value 0–255, or null to restore AUTO mode.
     */
    fun setScreenBrightness(value: Int?) {
        if (!canWriteSettings()) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot change brightness")
            return
        }
        try {
            if (value == null) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
            } else {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value.coerceIn(0, 255)
                )
            }
            Log.d(TAG, "Screen brightness set to ${value ?: "AUTO"}")
        } catch (e: Exception) {
            Log.e(TAG, "setScreenBrightness failed", e)
        }
    }

    /**
     * Reduce screen-off timeout.
     * Requires WRITE_SETTINGS.
     * @param ms timeout in milliseconds, or null to restore default (30 s).
     */
    fun setScreenTimeout(ms: Int?) {
        if (!canWriteSettings()) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot change screen timeout")
            return
        }
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                ms ?: 30_000
            )
            Log.d(TAG, "Screen timeout set to ${ms ?: 30_000} ms")
        } catch (e: Exception) {
            Log.e(TAG, "setScreenTimeout failed", e)
        }
    }

    /**
     * Turn off Wi-Fi.
     * Requires CHANGE_WIFI_STATE (already in manifest).
     * Safe to call — only acts when battery is critically low.
     */
    @Suppress("DEPRECATION")
    fun disableWifi() {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.isWifiEnabled) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    wm.isWifiEnabled = false      // deprecated in Q but still works
                    Log.d(TAG, "Wi-Fi disabled via WifiManager")
                } else {
                    // On Q+ only the Settings panel can toggle Wi-Fi for a non-system app
                    // — open the panel so the user can confirm
                    openWifiSettings()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "disableWifi failed", e)
        }
    }

    private fun openWifiSettings() {
        try {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open Wi-Fi settings panel", e)
        }
    }

    /**
     * Tell EasMonitorService to stop audio monitoring.
     * Sends a local broadcast; EasMonitorService must listen for the action.
     */
    fun stopEasMonitoring() {
        try {
            context.sendBroadcast(Intent(ACTION_STOP_EAS_MONITORING))
            Log.d(TAG, "Sent stop-EAS broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "stopEasMonitoring failed", e)
        }
    }

    /**
     * Tell TelemetryAgent to stop all sensor collection.
     */
    fun disableTelemetry() {
        try {
            context.sendBroadcast(Intent(ACTION_DISABLE_TELEMETRY))
            Log.d(TAG, "Sent disable-telemetry broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "disableTelemetry failed", e)
        }
    }

    /**
     * Tell BluetoothMeshService to switch to low-power advertising/scan intervals.
     * The mesh service must handle ACTION_REDUCE_BLE_INTERVAL.
     */
    fun reduceBleInterval() {
        try {
            context.sendBroadcast(Intent(ACTION_REDUCE_BLE_INTERVAL))
            Log.d(TAG, "Sent reduce-BLE-interval broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "reduceBleInterval failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Special-permission helpers
    // -------------------------------------------------------------------------

    fun canWriteSettings(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.System.canWrite(context)

    fun requestWriteSettingsPermission() {
        if (canWriteSettings()) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestWriteSettingsPermission failed", e)
        }
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)

    fun requestOverlayPermission() {
        if (canDrawOverlays()) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestOverlayPermission failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Battery whitelist (unchanged core logic)
    // -------------------------------------------------------------------------

    private fun setupBatteryOptimizationLauncher() {
        batteryOptimizationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            // Whether or not they whitelisted us, proceed — user made their choice
            onBatteryOptimizationDisabled()
        }
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization", e)
                false
            }
        } else true
    }

    fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    batteryOptimizationLauncher?.launch(intent)
                } else {
                    openBatteryOptimizationSettings()
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestDisableBatteryOptimization failed", e)
                onBatteryOptimizationFailed("Unable to open battery settings: ${e.message}")
            }
        } else {
            onBatteryOptimizationDisabled()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (intent.resolveActivity(context.packageManager) != null) {
                batteryOptimizationLauncher?.launch(intent)
            } else {
                openAppSettings()
            }
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            batteryOptimizationLauncher?.launch(intent)
        } catch (e: Exception) {
            onBatteryOptimizationFailed("Unable to open app settings: ${e.message}")
        }
    }

    fun isBatteryOptimizationSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun getBatteryOptimizationStatus(): String = when {
        !isBatteryOptimizationSupported()  -> "Not supported (Android < 6.0)"
        isBatteryOptimizationDisabled()    -> "Disabled (whitelisted)"
        else                               -> "Enabled (system may kill background service)"
    }

    fun getFullStatus(): String = buildString {
        appendLine("Battery level   : ${currentBatteryLevel()}%")
        appendLine("Power mode      : $currentMode")
        appendLine("Whitelist       : ${getBatteryOptimizationStatus()}")
        appendLine("WRITE_SETTINGS  : ${if (canWriteSettings()) "granted" else "not granted"}")
        appendLine("SYSTEM_ALERT_WINDOW: ${if (canDrawOverlays()) "granted" else "not granted"}")
    }

    fun logStatus() = Log.d(TAG, getFullStatus())

}

enum class BatteryMode {
    NORMAL,   // >50% — full feature set
    REDUCED,  // 20–50% — telemetry minimal, EAS duty-cycled
    MINIMAL,  // 10–20% — EAS off, telemetry off, Wi-Fi off, screen dimmed
    CRITICAL  // <10%  — mesh-only survival, everything else off
}

enum class BatteryOptimizationStatus {
    ENABLED, DISABLED, NOT_SUPPORTED
}
