package com.bitchat.android.ui.connectivity

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.telemetry.Telemeter
import com.bitchat.android.telemetry.SensorID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TelemetryTestViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<Application>().applicationContext

    private val _categories = MutableStateFlow(buildInitialCategories())
    val categories: StateFlow<List<TestCategory>> = _categories.asStateFlow()

    private val _isRunningAll = MutableStateFlow(false)
    val isRunningAll: StateFlow<Boolean> = _isRunningAll.asStateFlow()

    private val _meshTransmitEnabled = MutableStateFlow(false)
    val meshTransmitEnabled: StateFlow<Boolean> = _meshTransmitEnabled.asStateFlow()

    private val _lastPackedSize = MutableStateFlow(0)
    val lastPackedSize: StateFlow<Int> = _lastPackedSize.asStateFlow()

    private var telemeter: Telemeter? = null

    fun toggleMeshTransmit() {
        _meshTransmitEnabled.value = !_meshTransmitEnabled.value
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
            packAndReport()
            _isRunningAll.value = false
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
            delay(50)
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

    private fun updateTestResult(categoryId: String, testId: String, result: TelemetryTestResult) {
        _categories.value = _categories.value.map { cat ->
            if (cat.id == categoryId) {
                cat.copy(items = cat.items.map { item ->
                    if (item.id == testId) item.copy(status = result.status, detail = result.detail) else item
                })
            } else cat
        }
    }

    private fun packAndReport() {
        try {
            val t = getOrCreateTelemeter()
            val packed = t.packed()
            _lastPackedSize.value = packed.size
            Log.d(TAG, "Telemetry packed: ${packed.size} bytes, mesh=${_meshTransmitEnabled.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Pack failed: ${e.message}")
        }
    }

    private fun getOrCreateTelemeter(): Telemeter {
        return telemeter ?: Telemeter(ctx).also { telemeter = it }
    }

    override fun onCleared() {
        super.onCleared()
        telemeter?.release()
        telemeter = null
    }

    // ─── Test execution dispatch ─────────────────────────────────────

    private suspend fun executeTest(categoryId: String, testId: String): TelemetryTestResult {
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(5000) {
                    when (categoryId) {
                        "environment" -> executeEnvironmentTest(testId)
                        "motion" -> executeMotionTest(testId)
                        "location_telem" -> executeLocationTelemetryTest(testId)
                        "power_telem" -> executePowerTelemetryTest(testId)
                        "system" -> executeSystemTest(testId)
                        "connectivity_telem" -> executeConnectivityTelemetryTest(testId)
                        "pack_transmit" -> executePackTransmitTest(testId)
                        else -> TelemetryTestResult(TestStatus.FAIL, "unknown category")
                    }
                } ?: TelemetryTestResult(TestStatus.FAIL, "timeout (5s)")
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, e.message?.take(80) ?: "error")
            }
        }
    }

    // ─── Category: Environment Sensors ───────────────────────────────

    private suspend fun executeEnvironmentTest(testId: String): TelemetryTestResult {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val t = getOrCreateTelemeter()

        return when (testId) {
            "pressure" -> readHardwareSensor(sm, Sensor.TYPE_PRESSURE, "pressure", "hPa", t)
            "temperature" -> readHardwareSensor(sm, Sensor.TYPE_AMBIENT_TEMPERATURE, "temperature", "\u00B0C", t)
            "humidity" -> readHardwareSensor(sm, Sensor.TYPE_RELATIVE_HUMIDITY, "humidity", "%", t)
            "ambient_light" -> readHardwareSensor(sm, Sensor.TYPE_LIGHT, "ambient_light", "lux", t)
            "proximity" -> readHardwareSensor(sm, Sensor.TYPE_PROXIMITY, "proximity", "cm", t)
            else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
        }
    }

    // ─── Category: Motion Sensors ────────────────────────────────────

    private suspend fun executeMotionTest(testId: String): TelemetryTestResult {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val t = getOrCreateTelemeter()

        return when (testId) {
            "acceleration" -> readHardwareSensor(sm, Sensor.TYPE_ACCELEROMETER, "acceleration", "m/s\u00B2", t)
            "gravity" -> readHardwareSensor(sm, Sensor.TYPE_GRAVITY, "gravity", "m/s\u00B2", t)
            "gyroscope" -> readHardwareSensor(sm, Sensor.TYPE_GYROSCOPE, "angular_velocity", "rad/s", t)
            "magnetic_field" -> readHardwareSensor(sm, Sensor.TYPE_MAGNETIC_FIELD, "magnetic_field", "\u00B5T", t)
            else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
        }
    }

    // ─── Category: Location Telemetry ────────────────────────────────

    private suspend fun executeLocationTelemetryTest(testId: String): TelemetryTestResult = when (testId) {
        "gps_read" -> {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                TelemetryTestResult(TestStatus.FAIL, "needs ACCESS_FINE_LOCATION")
            } else {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                try {
                    val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: (if (Build.VERSION.SDK_INT >= 31) lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER) else null)
                    if (lastLoc != null) {
                        withContext(Dispatchers.Main) {
                            getOrCreateTelemeter().enable("location")
                        }
                        TelemetryTestResult(
                            TestStatus.PASS,
                            "%.5f, %.5f (acc: %dm) | telemetry: enabled".format(
                                lastLoc.latitude, lastLoc.longitude, lastLoc.accuracy.toInt()
                            )
                        )
                    } else {
                        withContext(Dispatchers.Main) {
                            getOrCreateTelemeter().enable("location")
                        }
                        val providers = lm.getProviders(true)
                        TelemetryTestResult(
                            TestStatus.AVAILABLE,
                            "no cached fix (open Maps first) | providers: ${providers.joinToString()} | sensor registered"
                        )
                    }
                } catch (e: SecurityException) {
                    TelemetryTestResult(TestStatus.FAIL, "permission denied")
                }
            }
        }
        "location_pack" -> {
            withContext(Dispatchers.Main) {
                getOrCreateTelemeter().enable("location")
            }
            delay(500)
            val data = getOrCreateTelemeter().read("location")
            if (data != null) {
                TelemetryTestResult(TestStatus.PASS, "location data packable | $data")
            } else {
                TelemetryTestResult(TestStatus.AVAILABLE, "sensor registered, awaiting GPS fix (open Maps to warm up GPS)")
            }
        }
        else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category: Power Telemetry ───────────────────────────────────

    private fun executePowerTelemetryTest(testId: String): TelemetryTestResult = when (testId) {
        "battery_telem" -> {
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
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempRaw / 10f
                val t = getOrCreateTelemeter()
                t.enable("battery")
                TelemetryTestResult(TestStatus.PASS, "$pct% | $chargingStr | ${tempC}\u00B0C | telemetry: enabled")
            } else {
                TelemetryTestResult(TestStatus.FAIL, "battery intent unavailable")
            }
        }
        "thermal_telem" -> {
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
                TelemetryTestResult(TestStatus.PASS, "thermal: $statusStr")
            } else {
                TelemetryTestResult(TestStatus.UNAVAILABLE, "requires API 29+")
            }
        }
        "power_consumption" -> {
            val t = getOrCreateTelemeter()
            t.enable("power_consumption")
            TelemetryTestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "sensor registered (needs runtime data)")
        }
        else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category: System Telemetry ──────────────────────────────────

    private fun executeSystemTest(testId: String): TelemetryTestResult = when (testId) {
        "processor" -> {
            val t = getOrCreateTelemeter()
            t.enable("processor")
            val cores = Runtime.getRuntime().availableProcessors()
            TelemetryTestResult(TestStatus.PASS, "$cores cores | telemetry: enabled")
        }
        "ram" -> {
            val t = getOrCreateTelemeter()
            t.enable("ram")
            val rt = Runtime.getRuntime()
            val usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMB = rt.maxMemory() / (1024 * 1024)
            TelemetryTestResult(TestStatus.PASS, "${usedMB}MB / ${maxMB}MB | telemetry: enabled")
        }
        "storage" -> {
            val t = getOrCreateTelemeter()
            t.enable("nvm")
            val usable = ctx.filesDir.usableSpace
            val total = ctx.filesDir.totalSpace
            val usableGB = String.format("%.1f", usable / 1_073_741_824.0)
            val totalGB = String.format("%.1f", total / 1_073_741_824.0)
            TelemetryTestResult(TestStatus.PASS, "${usableGB}GB free / ${totalGB}GB total | telemetry: enabled")
        }
        "time_sensor" -> {
            val t = getOrCreateTelemeter()
            val timeData = t.read("time")
            if (timeData != null) {
                TelemetryTestResult(TestStatus.PASS, "epoch: ${System.currentTimeMillis() / 1000} | telemetry: active")
            } else {
                TelemetryTestResult(TestStatus.FAIL, "time sensor not active")
            }
        }
        else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category: Connectivity Telemetry ──────────────────────────────

    private fun executeConnectivityTelemetryTest(testId: String): TelemetryTestResult = when (testId) {
        "connectivity_probe" -> {
            try {
                val t = getOrCreateTelemeter()
                t.enable("connectivity")
                val data = t.read("connectivity")
                if (data is com.bitchat.android.telemetry.sensors.ConnectivitySensor.ConnectivitySnapshot) {
                    val caps = mutableListOf<String>()
                    val labels = listOf(
                        "BLE", "BLE-Adv", "GPS", "Net-Loc", "WiFi-Direct", "WiFi-Aware",
                        "Internet", "Doze-Exempt", "Cam-Rear", "Cam-Front",
                        "Accel", "Gyro", "Baro", "Mag", "Light", "Prox",
                        "TTS", "ASR", "Tel", "SIM", "Charging"
                    )
                    for (i in labels.indices) {
                        if ((data.capabilities and (1 shl i)) != 0) caps.add(labels[i])
                    }
                    TelemetryTestResult(
                        TestStatus.PASS,
                        "${caps.size} caps: ${caps.joinToString(", ").take(100)} | batt=${data.batteryPercent}% | api=${data.apiLevel}"
                    )
                } else {
                    TelemetryTestResult(TestStatus.FAIL, "probe returned no data")
                }
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, "probe error: ${e.message?.take(60)}")
            }
        }
        "connectivity_pack" -> {
            try {
                val t = getOrCreateTelemeter()
                t.enable("connectivity")
                t.read("connectivity")
                val packed = t.packed()
                val restored = Telemeter.fromPacked(ctx, packed)
                val connData = restored?.read("connectivity")
                if (connData != null) {
                    TelemetryTestResult(TestStatus.PASS, "connectivity round-trip OK (${packed.size}B)")
                } else {
                    TelemetryTestResult(TestStatus.FAIL, "connectivity missing after unpack")
                }
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, "pack error: ${e.message?.take(60)}")
            }
        }
        "connectivity_mesh" -> {
            val t = getOrCreateTelemeter()
            t.enable("connectivity")
            t.read("connectivity")
            if (_meshTransmitEnabled.value) {
                val packed = t.packed()
                TelemetryTestResult(TestStatus.PASS, "mesh ON | ${packed.size}B with connectivity ready to broadcast")
            } else {
                TelemetryTestResult(TestStatus.AVAILABLE, "mesh OFF (enable to broadcast connectivity to peers)")
            }
        }
        else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Category: Pack & Transmit ───────────────────────────────────

    private fun executePackTransmitTest(testId: String): TelemetryTestResult = when (testId) {
        "pack_all" -> {
            try {
                val t = getOrCreateTelemeter()
                val packed = t.packed()
                _lastPackedSize.value = packed.size
                TelemetryTestResult(TestStatus.PASS, "${packed.size} bytes packed from active sensors")
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, "pack error: ${e.message?.take(60)}")
            }
        }
        "unpack_verify" -> {
            try {
                val t = getOrCreateTelemeter()
                val packed = t.packed()
                val restored = Telemeter.fromPacked(ctx, packed)
                if (restored != null) {
                    val rendered = restored.render()
                    TelemetryTestResult(TestStatus.PASS, "unpacked OK | ${rendered.size} sensor(s) rendered")
                } else {
                    TelemetryTestResult(TestStatus.FAIL, "unpack returned null")
                }
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, "round-trip failed: ${e.message?.take(60)}")
            }
        }
        "render_all" -> {
            try {
                val t = getOrCreateTelemeter()
                val rendered = t.render()
                val sensorNames = rendered.mapNotNull { it["name"] as? String }
                TelemetryTestResult(
                    TestStatus.PASS,
                    "${rendered.size} sensor(s): ${sensorNames.joinToString(", ").take(80)}"
                )
            } catch (e: Exception) {
                TelemetryTestResult(TestStatus.FAIL, "render error: ${e.message?.take(60)}")
            }
        }
        "mesh_ready" -> {
            if (_meshTransmitEnabled.value) {
                val packed = try { getOrCreateTelemeter().packed() } catch (_: Exception) { null }
                if (packed != null && packed.isNotEmpty()) {
                    TelemetryTestResult(TestStatus.PASS, "mesh transmit ON | ${packed.size}B ready")
                } else {
                    TelemetryTestResult(TestStatus.FAIL, "mesh ON but no data to send")
                }
            } else {
                TelemetryTestResult(TestStatus.AVAILABLE_NOT_IMPLEMENTED, "mesh transmit OFF (tap share icon to enable)")
            }
        }
        else -> TelemetryTestResult(TestStatus.FAIL, "unknown test")
    }

    // ─── Hardware sensor helper ──────────────────────────────────────

    private suspend fun readHardwareSensor(
        sm: SensorManager,
        sensorType: Int,
        telemetryName: String,
        unit: String,
        telemeter: Telemeter
    ): TelemetryTestResult {
        val sensor = sm.getDefaultSensor(sensorType)
            ?: return TelemetryTestResult(TestStatus.UNAVAILABLE, "sensor not present on device")

        telemeter.enable(telemetryName)

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        val values = event.values.take(3).joinToString(", ") { String.format("%.2f", it) }
                        val detail = "$values $unit | ${sensor.name} | telemetry: enabled"
                        if (cont.isActive) cont.resume(TelemetryTestResult(TestStatus.PASS, detail)) {}
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }

                viewModelScope.launch {
                    delay(3000)
                    sm.unregisterListener(listener)
                    if (cont.isActive) {
                        cont.resume(
                            TelemetryTestResult(TestStatus.AVAILABLE, "${sensor.name} (no data in 3s) | telemetry: enabled")
                        ) {}
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(ctx, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ─── AI-triggerable interface ────────────────────────────────────

    fun getActiveSensorNames(): List<String> {
        return telemeter?.let { t ->
            val rendered = t.render()
            rendered.mapNotNull { it["name"] as? String }
        } ?: emptyList()
    }

    fun getPackedTelemetry(): ByteArray? {
        return try { telemeter?.packed() } catch (_: Exception) { null }
    }

    fun enableSensor(name: String) {
        getOrCreateTelemeter().enable(name)
    }

    fun disableSensor(name: String) {
        getOrCreateTelemeter().disable(name)
    }

    fun getReadings(): Map<String, Any?> {
        return telemeter?.readAll() ?: emptyMap()
    }

    // ─── Category definitions ────────────────────────────────────────

    private fun buildInitialCategories(): List<TestCategory> = listOf(
        TestCategory(
            id = "environment",
            name = "environment sensors",
            icon = Icons.Outlined.Thermostat,
            items = listOf(
                TestItem("pressure", "barometer", "Atmospheric pressure (floor/altitude detection)"),
                TestItem("temperature", "temperature", "Ambient temperature"),
                TestItem("humidity", "humidity", "Relative humidity"),
                TestItem("ambient_light", "ambient light", "Luminosity (darkness/daylight detection)"),
                TestItem("proximity", "proximity", "Proximity sensor (pocket detection)"),
            )
        ),
        TestCategory(
            id = "motion",
            name = "motion & orientation",
            icon = Icons.Outlined.ScreenRotation,
            items = listOf(
                TestItem("acceleration", "accelerometer", "3-axis acceleration (seismic, fall detection)"),
                TestItem("gravity", "gravity", "Gravity vector (orientation)"),
                TestItem("gyroscope", "gyroscope", "Angular velocity (dead reckoning)"),
                TestItem("magnetic_field", "magnetometer", "Magnetic field (compass heading)"),
            )
        ),
        TestCategory(
            id = "location_telem",
            name = "location telemetry",
            icon = Icons.Outlined.MyLocation,
            items = listOf(
                TestItem("gps_read", "GPS read", "Read last known location", requiresPermission = Manifest.permission.ACCESS_FINE_LOCATION),
                TestItem("location_pack", "location pack", "Pack location for mesh transmission", requiresPermission = Manifest.permission.ACCESS_FINE_LOCATION),
            )
        ),
        TestCategory(
            id = "power_telem",
            name = "power telemetry",
            icon = Icons.Outlined.BatteryStd,
            items = listOf(
                TestItem("battery_telem", "battery", "Battery level, charging state, temperature"),
                TestItem("thermal_telem", "thermal status", "Device thermal monitoring (API 29+)"),
                TestItem("power_consumption", "power consumption", "Power usage tracking", isImplemented = false),
            )
        ),
        TestCategory(
            id = "system",
            name = "system telemetry",
            icon = Icons.Outlined.Memory,
            items = listOf(
                TestItem("time_sensor", "time sensor", "Epoch timestamp (always active)"),
                TestItem("processor", "CPU", "Processor core count and load"),
                TestItem("ram", "RAM", "Memory usage"),
                TestItem("storage", "storage", "Non-volatile memory usage"),
            )
        ),
        TestCategory(
            id = "connectivity_telem",
            name = "connectivity telemetry",
            icon = Icons.Outlined.WifiTethering,
            items = listOf(
                TestItem("connectivity_probe", "probe capabilities", "Scan all device capabilities into bitmask"),
                TestItem("connectivity_pack", "pack & verify", "Round-trip serialize/deserialize connectivity"),
                TestItem("connectivity_mesh", "mesh broadcast", "Broadcast connectivity status to mesh peers"),
            )
        ),
        TestCategory(
            id = "pack_transmit",
            name = "pack & transmit",
            icon = Icons.AutoMirrored.Outlined.Send,
            items = listOf(
                TestItem("pack_all", "pack telemetry", "Serialize all active sensors to binary"),
                TestItem("unpack_verify", "unpack verify", "Round-trip pack/unpack verification"),
                TestItem("render_all", "render all", "Render all active sensors for UI display"),
                TestItem("mesh_ready", "mesh transmit", "Check mesh broadcast readiness"),
            )
        ),
    )

    companion object {
        private const val TAG = "TelemetryTestVM"
    }
}

private data class TelemetryTestResult(
    val status: TestStatus,
    val detail: String? = null
)
