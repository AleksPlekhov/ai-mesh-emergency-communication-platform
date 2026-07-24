package com.bitchat.android.ui.connectivity

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectivityTestViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _categories = MutableStateFlow(buildInitialCategories())
    val categories: StateFlow<List<TestCategory>> = _categories.asStateFlow()

    private val _isRunningAll = MutableStateFlow(false)
    val isRunningAll: StateFlow<Boolean> = _isRunningAll.asStateFlow()

    private val _meshShareEnabled = MutableStateFlow(false)
    val meshShareEnabled: StateFlow<Boolean> = _meshShareEnabled.asStateFlow()

    private val _lastPackedSize = MutableStateFlow(0)
    val lastPackedSize: StateFlow<Int> = _lastPackedSize.asStateFlow()

    fun toggleMeshShare() {
        _meshShareEnabled.value = !_meshShareEnabled.value
        if (_meshShareEnabled.value) {
            packTestResults()
            sendTelemetryOverMesh()
        }
    }

    /**
     * Broadcast telemetry on the main channel and as private messages to all peers.
     */
    fun sendTelemetryOverMesh() {
        val agent = com.bitchat.android.telemetry.TelemetryAgent.getInstance(ctx)
        // Enable basic sensors for this snapshot
        agent.enableSensor("battery")
        agent.enableSensor("time")
        agent.enableSensor("connectivity")
        // Broadcast to main channel
        agent.broadcastToMainChannel()
        // Also send privately to every connected peer
        agent.sendToAllPeers()
    }

    fun packTestResults(): ByteArray? {
        return try {
            val packed = packConnectivitySnapshot()
            _lastPackedSize.value = packed.size
            packed
        } catch (_: Exception) { null }
    }

    private fun packConnectivitySnapshot(): ByteArray {
        val cats = _categories.value
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeByte(0x01) // format version
        dos.writeLong(System.currentTimeMillis() / 1000)
        dos.writeShort(cats.size)
        for (cat in cats) {
            dos.writeUTF(cat.id)
            dos.writeShort(cat.items.size)
            for (item in cat.items) {
                dos.writeUTF(item.id)
                dos.writeByte(item.status.ordinal)
                dos.writeUTF(item.detail?.take(200) ?: "")
            }
        }
        return out.toByteArray()
    }

    fun toggleCategory(categoryId: String) {
        _categories.value = _categories.value.map {
            if (it.id == categoryId) it.copy(isExpanded = !it.isExpanded) else it
        }
    }

    fun runAllTests() {
        viewModelScope.launch {
            _isRunningAll.value = true
            _categories.value.forEach { cat ->
                runCategoryTestsInternal(cat.id)
            }
            _isRunningAll.value = false
            if (_meshShareEnabled.value) packTestResults()
        }
    }

    fun runCategoryTests(categoryId: String) {
        viewModelScope.launch { runCategoryTestsInternal(categoryId) }
    }

    fun runSingleTest(categoryId: String, testId: String) {
        viewModelScope.launch {
            updateTestStatus(categoryId, testId, TestStatus.TESTING)
            val result = executeTest(categoryId, testId)
            updateTestResult(categoryId, testId, result)
        }
    }

    private suspend fun runCategoryTestsInternal(categoryId: String) {
        val cat = _categories.value.find { it.id == categoryId } ?: return
        for (item in cat.items) {
            updateTestStatus(categoryId, item.id, TestStatus.TESTING)
            val result = executeTest(categoryId, item.id)
            updateTestResult(categoryId, item.id, result)
            delay(50) // brief pause for UI updates
        }
    }

    private fun updateTestStatus(categoryId: String, testId: String, status: TestStatus) {
        _categories.value = _categories.value.map { cat ->
            if (cat.id == categoryId) {
                cat.copy(items = cat.items.map { item ->
                    if (item.id == testId) item.copy(status = status, detail = null) else item
                })
            } else cat
        }
    }

    private fun updateTestResult(categoryId: String, testId: String, result: TestResult) {
        _categories.value = _categories.value.map { cat ->
            if (cat.id == categoryId) {
                cat.copy(items = cat.items.map { item ->
                    if (item.id == testId) item.copy(status = result.status, detail = result.detail) else item
                })
            } else cat
        }
    }

    // ─── Test execution dispatch ─────────────────────────────────────

    private suspend fun executeTest(categoryId: String, testId: String): TestResult {
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(8000) {
                    when (categoryId) {
                        "satellite" -> executeSatelliteTest(testId)
                        "location" -> executeLocationTest(testId)
                        "sensors" -> executeSensorTest(testId)
                        "p2p" -> executeP2PTest(testId)
                        "background" -> executeBackgroundTest(testId)
                        "audio" -> executeAudioTest(testId)
                        "camera" -> executeCameraTest(testId)
                        "storage" -> executeStorageTest(testId)
                        "power" -> executePowerTest(testId)
                        "health" -> executeHealthTest(testId)
                        "telephony" -> executeTelephonyTest(testId)
                        "accessibility" -> executeAccessibilityTest(testId)
                        else -> TestResult(TestStatus.FAIL, "unknown category")
                    }
                } ?: TestResult(TestStatus.FAIL, "timeout (5s)")
            } catch (e: Exception) {
                TestResult(TestStatus.FAIL, e.message?.take(80) ?: "error")
            }
        }
    }

    // ─── Category 1: Satellite & Constrained Network ─────────────────

    private fun executeSatelliteTest(testId: String): TestResult = when (testId) {
        "satellite_transport" -> {
            if (Build.VERSION.SDK_INT >= 35) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networks = cm.allNetworks
                val hasSatellite = networks.any { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_SATELLITE)
                }
                if (hasSatellite) TestResult(TestStatus.PASS, "satellite transport detected")
                else TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "API available, no satellite network active")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 35+ (device: ${Build.VERSION.SDK_INT})")
            }
        }
        "sms_capability" -> {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm?.isSmsCapable == true) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "SMS capable (satellite SMS not implemented)")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "device not SMS capable")
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 2: Offline Location & GNSS ─────────────────────────

    private suspend fun executeLocationTest(testId: String): TestResult = when (testId) {
        "gps_provider" -> {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val providers = lm.allProviders
            if (gpsEnabled) TestResult(TestStatus.PASS, "GPS enabled | providers: ${providers.joinToString()}")
            else TestResult(TestStatus.FAIL, "GPS disabled | providers: ${providers.joinToString()}")
        }
        "gnss_raw" -> {
            if (Build.VERSION.SDK_INT >= 24) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "GNSS raw measurements API available (API ${Build.VERSION.SDK_INT})")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 24+")
            }
        }
        "satellite_count" -> {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                TestResult(TestStatus.FAIL, "needs ACCESS_FINE_LOCATION permission")
            } else if (Build.VERSION.SDK_INT < 24) {
                TestResult(TestStatus.UNAVAILABLE, "GnssStatus requires API 24+")
            } else {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                try {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val callback = object : GnssStatus.Callback() {
                                override fun onSatelliteStatusChanged(status: GnssStatus) {
                                    lm.unregisterGnssStatusCallback(this)
                                    val total = status.satelliteCount
                                    var usedInFix = 0
                                    val constellations = mutableSetOf<String>()
                                    for (i in 0 until total) {
                                        if (status.usedInFix(i)) usedInFix++
                                        constellations.add(when (status.getConstellationType(i)) {
                                            GnssStatus.CONSTELLATION_GPS -> "GPS"
                                            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                                            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                                            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                                            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                                            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                                            else -> "other"
                                        })
                                    }
                                    if (cont.isActive) {
                                        cont.resume(TestResult(
                                            TestStatus.PASS,
                                            "$total visible | $usedInFix in fix | ${constellations.joinToString()}"
                                        )) {}
                                    }
                                }
                            }
                            lm.registerGnssStatusCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
                            cont.invokeOnCancellation {
                                lm.unregisterGnssStatusCallback(callback)
                            }
                            viewModelScope.launch {
                                delay(4000)
                                lm.unregisterGnssStatusCallback(callback)
                                if (cont.isActive) {
                                    val lastLoc = try {
                                        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    } catch (_: SecurityException) { null }
                                    val locDetail = if (lastLoc != null) {
                                        "last fix: %.5f, %.5f (acc: %dm)".format(
                                            lastLoc.latitude, lastLoc.longitude, lastLoc.accuracy.toInt()
                                        )
                                    } else "no cached fix"
                                    cont.resume(TestResult(
                                        TestStatus.AVAILABLE,
                                        "no GNSS status in 4s (GPS may be cold) | $locDetail"
                                    )) {}
                                }
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    TestResult(TestStatus.FAIL, "permission denied: ${e.message?.take(40)}")
                }
            }
        }
        "fused_location" -> {
            val hasGms = try {
                ctx.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            if (hasGms) TestResult(TestStatus.PASS, "Google Play Services available")
            else TestResult(TestStatus.FAIL, "Google Play Services not found")
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 3: Device Sensors ──────────────────────────────────

    private suspend fun executeSensorTest(testId: String): TestResult {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val sensorTypeMap = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "barometer" to Sensor.TYPE_PRESSURE,
            "ambient_light" to Sensor.TYPE_LIGHT,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "significant_motion" to Sensor.TYPE_SIGNIFICANT_MOTION,
            "step_detector" to 18 // Sensor.TYPE_STEP_DETECTOR
        )

        val sensorType = sensorTypeMap[testId]
            ?: return TestResult(TestStatus.FAIL, "unknown sensor test")

        val sensor = sm.getDefaultSensor(sensorType)
            ?: return TestResult(TestStatus.UNAVAILABLE, "sensor not present on device")

        // For significant motion and step detector, just check presence
        if (testId == "significant_motion" || testId == "step_detector") {
            return TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "${sensor.name} (${sensor.vendor})")
        }

        // For other sensors, read a live value on the main thread (SensorManager requires Looper)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        val values = event.values.take(3).joinToString(", ") { String.format("%.2f", it) }
                        val unit = when (testId) {
                            "barometer" -> "hPa"
                            "ambient_light" -> "lux"
                            "accelerometer", "magnetometer" -> "m/s²"
                            "gyroscope" -> "rad/s"
                            "proximity" -> "cm"
                            else -> ""
                        }
                        val detail = "$values $unit | ${sensor.name}"
                        if (cont.isActive) cont.resume(TestResult(TestStatus.PASS, detail)) {}
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }

                // Timeout fallback — if no event within 3s, still report available
                viewModelScope.launch {
                    delay(3000)
                    sm.unregisterListener(listener)
                    if (cont.isActive) cont.resume(TestResult(TestStatus.AVAILABLE, "${sensor.name} (no data in 3s)")) {}
                }
            }
        }
    }

    // ─── Category 4: P2P Connectivity ────────────────────────────────

    private fun executeP2PTest(testId: String): TestResult = when (testId) {
        "ble" -> {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            if (adapter == null) {
                TestResult(TestStatus.UNAVAILABLE, "no Bluetooth adapter")
            } else {
                val adv = adapter.bluetoothLeAdvertiser != null
                val scan = adapter.bluetoothLeScanner != null
                val enabled = adapter.isEnabled
                TestResult(
                    if (enabled) TestStatus.PASS else TestStatus.FAIL,
                    "enabled=$enabled adv=$adv scan=$scan | ${adapter.name ?: "unnamed"}"
                )
            }
        }
        "wifi_direct" -> {
            val wpm = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wpm != null) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "Wi-Fi Direct API available")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "Wi-Fi P2P not supported")
            }
        }
        "wifi_aware" -> {
            if (Build.VERSION.SDK_INT >= 26) {
                val hasFeature = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
                if (hasFeature) {
                    TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "Wi-Fi Aware (NAN) supported")
                } else {
                    TestResult(TestStatus.UNAVAILABLE, "Wi-Fi Aware not supported by hardware")
                }
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 26+")
            }
        }
        "nearby_connections" -> {
            val hasGms = try {
                ctx.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            if (hasGms) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "Play Services present (Nearby not integrated)")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires Google Play Services")
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 5: Background Execution ────────────────────────────

    private fun executeBackgroundTest(testId: String): TestResult = when (testId) {
        "doze_exemption" -> {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            if (exempt) TestResult(TestStatus.PASS, "battery optimization disabled")
            else TestResult(TestStatus.FAIL, "not exempt from battery optimization")
        }
        "exact_alarms" -> {
            if (Build.VERSION.SDK_INT >= 31) {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val can = am.canScheduleExactAlarms()
                if (can) TestResult(TestStatus.PASS, "exact alarms permitted")
                else TestResult(TestStatus.FAIL, "exact alarms denied — request SCHEDULE_EXACT_ALARM")
            } else {
                TestResult(TestStatus.PASS, "exact alarms always allowed on API ${Build.VERSION.SDK_INT}")
            }
        }
        "boot_receiver" -> {
            val hasPermission = ctx.packageManager.checkPermission(
                "android.permission.RECEIVE_BOOT_COMPLETED", ctx.packageName
            ) == PackageManager.PERMISSION_GRANTED
            TestResult(
                TestStatus.AVAILABLE_NOT_IMPLEMENTED,
                "BOOT_COMPLETED ${if (hasPermission) "granted" else "not declared"} (receiver not implemented)"
            )
        }
        "foreground_service" -> {
            // Check if foreground service permissions are declared
            val hasFgPerm = ctx.packageManager.checkPermission(
                "android.permission.FOREGROUND_SERVICE", ctx.packageName
            ) == PackageManager.PERMISSION_GRANTED
            TestResult(
                TestStatus.AVAILABLE_NOT_IMPLEMENTED,
                "FOREGROUND_SERVICE ${if (hasFgPerm) "declared" else "not declared"} (service not implemented)"
            )
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 6: Audio & Alerts ──────────────────────────────────

    private suspend fun executeAudioTest(testId: String): TestResult = when (testId) {
        "tts_engine" -> {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(getApplication()) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val langs = try { tts?.availableLanguages?.size ?: 0 } catch (_: Exception) { 0 }
                            val engine = try { tts?.defaultEngine ?: "unknown" } catch (_: Exception) { "unknown" }
                            tts?.shutdown()
                            if (cont.isActive) cont.resume(TestResult(TestStatus.PASS, "engine: $engine | $langs languages")) {}
                        } else {
                            tts?.shutdown()
                            if (cont.isActive) cont.resume(TestResult(TestStatus.FAIL, "TTS init failed (status=$status) — check Settings > Accessibility > TTS")) {}
                        }
                    }
                    cont.invokeOnCancellation { tts?.shutdown() }
                    viewModelScope.launch {
                        delay(4000)
                        if (cont.isActive) {
                            tts?.shutdown()
                            cont.resume(TestResult(TestStatus.FAIL, "TTS init timed out (4s) — check Settings > Accessibility > TTS output")) {}
                        }
                    }
                }
            }
        }
        "vibration" -> {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val hasAmplitude = if (Build.VERSION.SDK_INT >= 26) vibrator.hasAmplitudeControl() else false
                TestResult(TestStatus.PASS, "vibrator present | amplitude control=$hasAmplitude")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "no vibrator")
            }
        }
        "notification_channels" -> {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channels = nm.notificationChannels
            val dndAccess = nm.isNotificationPolicyAccessGranted
            TestResult(TestStatus.PASS, "${channels.size} channels | DND access=$dndAccess")
        }
        "dnd_bypass" -> {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val granted = nm.isNotificationPolicyAccessGranted
            if (granted) {
                TestResult(TestStatus.PASS, "DND policy access granted — can override Do Not Disturb for emergency alerts")
            } else {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    TestResult(TestStatus.AVAILABLE,
                        "Settings opened → find \"bitchat\" in the list and toggle it ON, then re-run this test")
                } catch (_: Exception) {
                    TestResult(TestStatus.FAIL,
                        "Go to Settings > Apps > Special access > Do Not Disturb > enable \"bitchat\"")
                }
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 7: Camera & ML ─────────────────────────────────────

    private fun executeCameraTest(testId: String): TestResult = when (testId) {
        "camera_rear" -> {
            val has = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            if (has) TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "camera hardware present (CameraX not integrated)")
            else TestResult(TestStatus.UNAVAILABLE, "no camera hardware")
        }
        "camera_front" -> {
            val has = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
            if (has) TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "front camera present")
            else TestResult(TestStatus.UNAVAILABLE, "no front camera")
        }
        "ml_kit" -> {
            val available = try {
                Class.forName("com.google.mlkit.vision.barcode.BarcodeScanning")
                true
            } catch (_: ClassNotFoundException) { false }
            if (available) TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "ML Kit bundled on classpath")
            else TestResult(TestStatus.UNAVAILABLE, "ML Kit not included in dependencies")
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 8: Offline Storage ─────────────────────────────────

    private fun executeStorageTest(testId: String): TestResult = when (testId) {
        "internal_storage" -> {
            val usable = ctx.filesDir.usableSpace
            val total = ctx.filesDir.totalSpace
            val usableGB = String.format("%.1f", usable / 1_073_741_824.0)
            val totalGB = String.format("%.1f", total / 1_073_741_824.0)
            TestResult(TestStatus.PASS, "${usableGB}GB free / ${totalGB}GB total")
        }
        "encrypted_prefs" -> {
            try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    ctx,
                    "connectivity_test_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefs.edit().putString("test", "ok").apply()
                val read = prefs.getString("test", null)
                // Clean up test file
                prefs.edit().clear().apply()
                if (read == "ok") TestResult(TestStatus.PASS, "AES256 encrypted prefs working")
                else TestResult(TestStatus.FAIL, "write/read mismatch")
            } catch (e: Exception) {
                TestResult(TestStatus.FAIL, "EncryptedSharedPreferences failed: ${e.message?.take(60)}")
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 9: Power Management ────────────────────────────────

    private fun executePowerTest(testId: String): TestResult = when (testId) {
        "battery_level" -> {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (level * 100) / scale else -1
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val chargingStr = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "unplugged"
                }
                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
                val healthStr = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                }
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempRaw / 10f
                TestResult(TestStatus.PASS, "$pct% | $chargingStr | health=$healthStr | ${tempC}°C")
            } else {
                TestResult(TestStatus.FAIL, "battery intent unavailable")
            }
        }
        "thermal_status" -> {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val status = pm.currentThermalStatus
                val statusStr = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "none"
                    PowerManager.THERMAL_STATUS_LIGHT -> "light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                    else -> "unknown($status)"
                }
                TestResult(TestStatus.PASS, "thermal: $statusStr")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 29+ (device: ${Build.VERSION.SDK_INT})")
            }
        }
        "power_save" -> {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val powerSave = pm.isPowerSaveMode
            val interactive = pm.isInteractive
            TestResult(TestStatus.PASS, "power save=$powerSave | interactive=$interactive")
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 10: Health & Wearables ─────────────────────────────

    private fun executeHealthTest(testId: String): TestResult = when (testId) {
        "health_connect" -> {
            val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
            val resolved = ctx.packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "Health Connect app installed")
            } else {
                // Try the provider check for API 34+ built-in
                if (Build.VERSION.SDK_INT >= 34) {
                    TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "built-in Health Connect (API 34+)")
                } else {
                    TestResult(TestStatus.UNAVAILABLE, "Health Connect not installed")
                }
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 11: Emergency Telephony ────────────────────────────

    private fun executeTelephonyTest(testId: String): TestResult = when (testId) {
        "emergency_number_api" -> {
            if (Build.VERSION.SDK_INT >= 29) {
                TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "getEmergencyNumberList() available (API 29+)")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 29+")
            }
        }
        "telephony_present" -> {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val type = when (tm.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                    TelephonyManager.PHONE_TYPE_NONE -> "none"
                    else -> "unknown"
                }
                val simState = when (tm.simState) {
                    TelephonyManager.SIM_STATE_READY -> "ready"
                    TelephonyManager.SIM_STATE_ABSENT -> "absent"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
                    else -> "other"
                }
                TestResult(TestStatus.PASS, "type=$type | SIM=$simState")
            } else {
                TestResult(TestStatus.UNAVAILABLE, "no TelephonyManager")
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category 12: Accessibility ──────────────────────────────────

    private suspend fun executeAccessibilityTest(testId: String): TestResult = when (testId) {
        "speech_recognizer" -> {
            val available = SpeechRecognizer.isRecognitionAvailable(ctx)
            val onDevice = if (Build.VERSION.SDK_INT >= 33) {
                try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Exception) { null }
            } else null
            val hasGoogleApp = try {
                ctx.packageManager.getPackageInfo("com.google.android.googlequicksearchbox", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            val detail = buildString {
                append("cloud=$available")
                if (onDevice != null) append(" | on-device=$onDevice")
                append(" | Google app=${if (hasGoogleApp) "yes" else "NO (install for ASR)"}")
                if (!available && !hasGoogleApp) {
                    append("\n→ Install Google app or download offline speech in Settings > Languages")
                }
            }
            when {
                available || onDevice == true -> TestResult(TestStatus.PASS, detail)
                hasGoogleApp -> TestResult(TestStatus.AVAILABLE, "$detail\n→ Download offline speech: Settings > System > Languages > Speech")
                else -> TestResult(TestStatus.FAIL, detail)
            }
        }
        "tts_voices" -> {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(getApplication()) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val langs = try { tts?.availableLanguages?.size ?: 0 } catch (_: Exception) { 0 }
                            val voices = try { tts?.voices?.size ?: 0 } catch (_: Exception) { 0 }
                            val currentLang = try { tts?.language?.displayName ?: "default" } catch (_: Exception) { "unknown" }
                            val engine = try { tts?.defaultEngine ?: "?" } catch (_: Exception) { "?" }
                            tts?.shutdown()
                            if (cont.isActive) cont.resume(TestResult(
                                TestStatus.PASS,
                                "$langs languages | $voices voices | engine=$engine | lang=$currentLang"
                            )) {}
                        } else {
                            tts?.shutdown()
                            if (cont.isActive) cont.resume(TestResult(
                                TestStatus.FAIL,
                                "TTS init failed (status=$status) — check Settings > Accessibility > TTS output"
                            )) {}
                        }
                    }
                    cont.invokeOnCancellation { tts?.shutdown() }
                    viewModelScope.launch {
                        delay(4000)
                        if (cont.isActive) {
                            tts?.shutdown()
                            cont.resume(TestResult(TestStatus.FAIL, "TTS voices timed out (4s) — check TTS settings")) {}
                        }
                    }
                }
            }
        }
        "rich_haptics" -> {
            if (Build.VERSION.SDK_INT >= 30) {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator != null) {
                    val primitives = intArrayOf(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_TICK
                    )
                    val supported = vibrator.areAllPrimitivesSupported(*primitives)
                    if (supported) TestResult(TestStatus.PASS, "rich haptic primitives supported")
                    else TestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "basic vibration only (no rich primitives)")
                } else {
                    TestResult(TestStatus.UNAVAILABLE, "no vibrator")
                }
            } else {
                TestResult(TestStatus.UNAVAILABLE, "requires API 30+ (device: ${Build.VERSION.SDK_INT})")
            }
        }
        else -> TestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    // ─── Initial category definitions ────────────────────────────────

    private fun buildInitialCategories(): List<TestCategory> = listOf(
        TestCategory(
            id = "satellite",
            name = "satellite & constrained network",
            icon = Icons.Outlined.SatelliteAlt,
            items = listOf(
                TestItem("satellite_transport", "TRANSPORT_SATELLITE", "Detect satellite connectivity (API 34+)", isImplemented = false),
                TestItem("sms_capability", "SMS capability", "Check SMS/satellite SMS support", isImplemented = false),
            )
        ),
        TestCategory(
            id = "location",
            name = "offline location & GNSS",
            icon = Icons.Outlined.MyLocation,
            items = listOf(
                TestItem("gps_provider", "GPS provider", "Check GPS_PROVIDER status", requiresPermission = Manifest.permission.ACCESS_FINE_LOCATION),
                TestItem("gnss_raw", "GNSS raw measurements", "Raw pseudorange/carrier phase (API 24+)", isImplemented = false),
                TestItem("satellite_count", "satellite count", "GNSS satellite visibility", requiresPermission = Manifest.permission.ACCESS_FINE_LOCATION, isImplemented = false),
                TestItem("fused_location", "fused location", "Google Play Services FLP availability"),
            )
        ),
        TestCategory(
            id = "sensors",
            name = "device sensors",
            icon = Icons.Outlined.Sensors,
            items = listOf(
                TestItem("accelerometer", "accelerometer", "3-axis acceleration (seismic detection)"),
                TestItem("gyroscope", "gyroscope", "Angular velocity (dead reckoning)"),
                TestItem("barometer", "barometer", "Atmospheric pressure (floor detection)"),
                TestItem("ambient_light", "ambient light", "Luminosity (darkness detection)"),
                TestItem("magnetometer", "magnetometer", "Magnetic field (compass)"),
                TestItem("proximity", "proximity", "Proximity sensor"),
                TestItem("significant_motion", "significant motion", "Low-power wake-up trigger", isImplemented = false),
                TestItem("step_detector", "step detector", "Step counting (evacuation distance)", isImplemented = false),
            )
        ),
        TestCategory(
            id = "p2p",
            name = "P2P connectivity",
            icon = Icons.Outlined.WifiTethering,
            items = listOf(
                TestItem("ble", "Bluetooth LE", "BLE advertising & scanning"),
                TestItem("wifi_direct", "Wi-Fi Direct", "P2P high-throughput transfer", isImplemented = false),
                TestItem("wifi_aware", "Wi-Fi Aware (NAN)", "Automatic passive discovery", isImplemented = false),
                TestItem("nearby_connections", "Nearby Connections", "Google multi-radio P2P framework", isImplemented = false),
            )
        ),
        TestCategory(
            id = "background",
            name = "background execution",
            icon = Icons.Outlined.PowerSettingsNew,
            items = listOf(
                TestItem("doze_exemption", "Doze exemption", "Battery optimization whitelist status"),
                TestItem("exact_alarms", "exact alarms", "SCHEDULE_EXACT_ALARM permission"),
                TestItem("boot_receiver", "BOOT_COMPLETED", "Restart after reboot capability", isImplemented = false),
                TestItem("foreground_service", "foreground service", "FGS permission status", isImplemented = false),
            )
        ),
        TestCategory(
            id = "audio",
            name = "audio & alerts",
            icon = Icons.Outlined.VolumeUp,
            items = listOf(
                TestItem("tts_engine", "TTS engine", "Text-to-speech for spoken alerts"),
                TestItem("vibration", "vibration", "Haptic feedback / SOS morse"),
                TestItem("notification_channels", "notification channels", "Alert channel status"),
                TestItem("dnd_bypass", "DND bypass", "Do Not Disturb policy access"),
            )
        ),
        TestCategory(
            id = "camera",
            name = "camera & ML",
            icon = Icons.Outlined.CameraAlt,
            items = listOf(
                TestItem("camera_rear", "rear camera", "Structural damage capture", isImplemented = false),
                TestItem("camera_front", "front camera", "Survivor identification", isImplemented = false),
                TestItem("ml_kit", "ML Kit", "On-device ML inference", isImplemented = false),
            )
        ),
        TestCategory(
            id = "storage",
            name = "offline storage",
            icon = Icons.Outlined.Storage,
            items = listOf(
                TestItem("internal_storage", "internal storage", "Available space for offline data"),
                TestItem("encrypted_prefs", "encrypted preferences", "AES256 encrypted key-value store"),
            )
        ),
        TestCategory(
            id = "power",
            name = "power management",
            icon = Icons.Outlined.BatteryStd,
            items = listOf(
                TestItem("battery_level", "battery level", "Current charge and health"),
                TestItem("thermal_status", "thermal status", "Device temperature monitoring (API 29+)"),
                TestItem("power_save", "power save mode", "Battery saver status"),
            )
        ),
        TestCategory(
            id = "health",
            name = "health & wearables",
            icon = Icons.Outlined.MonitorHeart,
            items = listOf(
                TestItem("health_connect", "Health Connect", "Wearable health data access", isImplemented = false),
            )
        ),
        TestCategory(
            id = "telephony",
            name = "emergency telephony",
            icon = Icons.Outlined.Call,
            items = listOf(
                TestItem("emergency_number_api", "emergency number API", "Emergency number detection (API 29+)", isImplemented = false),
                TestItem("telephony_present", "telephony manager", "Phone type and SIM status"),
            )
        ),
        TestCategory(
            id = "accessibility",
            name = "accessibility",
            icon = Icons.Outlined.Accessibility,
            items = listOf(
                TestItem("speech_recognizer", "speech recognizer", "On-device voice commands", isImplemented = false),
                TestItem("tts_voices", "TTS voices", "Available language voices"),
                TestItem("rich_haptics", "rich haptics", "Advanced vibration primitives (API 30+)", isImplemented = false),
            )
        ),
    )
}

private data class TestResult(
    val status: TestStatus,
    val detail: String? = null
)
