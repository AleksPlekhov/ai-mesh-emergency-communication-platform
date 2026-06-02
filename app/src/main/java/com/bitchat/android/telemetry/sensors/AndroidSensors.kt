package com.bitchat.android.telemetry.sensors

import com.bitchat.android.telemetry.BaseSensor
import com.bitchat.android.telemetry.SensorID
import com.bitchat.android.telemetry.Telemeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.round

/**
 * Pressure Sensor
 */
class PressureSensor(private val context: Context) : BaseSensor(SensorID.PRESSURE, "pressure", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var pressureSensor: Sensor? = null
    private var pressureData: PressureData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        
        if (pressureSensor != null) {
            sensorManager?.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        pressureSensor = null
        pressureData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PRESSURE) {
            pressureData = PressureData(mbar = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = pressureData
    
    override fun packData(data: Any): ByteArray {
        val pressureData = data as PressureData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(pressureData.mbar).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val mbar = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        pressureData = PressureData(mbar = mbar)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return pressureData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = pressureData ?: return null
        return mapOf(
            "icon" to "weather-cloudy",
            "name" to "Ambient Pressure",
            "values" to mapOf("mbar" to data.mbar)
        )
    }
    
    data class PressureData(val mbar: Float)
}

/**
 * Temperature Sensor
 */
class TemperatureSensor(private val context: Context) : BaseSensor(SensorID.TEMPERATURE, "temperature", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var temperatureSensor: Sensor? = null
    private var temperatureData: TemperatureData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        temperatureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        
        if (temperatureSensor != null) {
            sensorManager?.registerListener(this, temperatureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        temperatureSensor = null
        temperatureData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            temperatureData = TemperatureData(c = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = temperatureData
    
    override fun packData(data: Any): ByteArray {
        val temperatureData = data as TemperatureData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(temperatureData.c).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val c = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        temperatureData = TemperatureData(c = c)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return temperatureData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = temperatureData ?: return null
        return mapOf(
            "icon" to "thermometer",
            "name" to "Temperature",
            "values" to mapOf("c" to data.c)
        )
    }
    
    data class TemperatureData(val c: Float)
}

/**
 * Humidity Sensor
 */
class HumiditySensor(private val context: Context) : BaseSensor(SensorID.HUMIDITY, "humidity", 5000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var humiditySensor: Sensor? = null
    private var humidityData: HumidityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        humiditySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        
        if (humiditySensor != null) {
            sensorManager?.registerListener(this, humiditySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        humiditySensor = null
        humidityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_RELATIVE_HUMIDITY) {
            humidityData = HumidityData(percentRelative = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = humidityData
    
    override fun packData(data: Any): ByteArray {
        val humidityData = data as HumidityData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(humidityData.percentRelative).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val percentRelative = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        humidityData = HumidityData(percentRelative = percentRelative)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return humidityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = humidityData ?: return null
        return mapOf(
            "icon" to "water-percent",
            "name" to "Relative Humidity",
            "values" to mapOf("percent" to data.percentRelative)
        )
    }
    
    data class HumidityData(val percentRelative: Float)
}

/**
 * Magnetic Field Sensor
 */
class MagneticFieldSensor(private val context: Context) : BaseSensor(SensorID.MAGNETIC_FIELD, "magnetic_field", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var magneticFieldSensor: Sensor? = null
    private var magneticFieldData: MagneticFieldData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magneticFieldSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (magneticFieldSensor != null) {
            sensorManager?.registerListener(this, magneticFieldSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        magneticFieldSensor = null
        magneticFieldData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticFieldData = MagneticFieldData(
                x = round(event.values[0] * 1e6f) / 1e6f,
                y = round(event.values[1] * 1e6f) / 1e6f,
                z = round(event.values[2] * 1e6f) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = magneticFieldData
    
    override fun packData(data: Any): ByteArray {
        val magneticFieldData = data as MagneticFieldData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(magneticFieldData.x)
        dos.writeFloat(magneticFieldData.y)
        dos.writeFloat(magneticFieldData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        magneticFieldData = MagneticFieldData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return magneticFieldData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = magneticFieldData ?: return null
        return mapOf(
            "icon" to "magnet",
            "name" to "Magnetic Field",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class MagneticFieldData(val x: Float, val y: Float, val z: Float)
}

/**
 * Ambient Light Sensor
 */
class AmbientLightSensor(private val context: Context) : BaseSensor(SensorID.AMBIENT_LIGHT, "ambient_light", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightData: LightData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        lightSensor = null
        lightData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            lightData = LightData(lux = round(event.values[0] * 100) / 100f)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = lightData
    
    override fun packData(data: Any): ByteArray {
        val lightData = data as LightData
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(lightData.lux).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val lux = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).float
        lightData = LightData(lux = lux)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return lightData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = lightData ?: return null
        return mapOf(
            "icon" to "white-balance-sunny",
            "name" to "Ambient Light",
            "values" to mapOf("lux" to data.lux)
        )
    }
    
    data class LightData(val lux: Float)
}

/**
 * Gravity Sensor
 */
class GravitySensor(private val context: Context) : BaseSensor(SensorID.GRAVITY, "gravity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var gravityData: GravityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        
        if (gravitySensor != null) {
            sensorManager?.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        gravitySensor = null
        gravityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY) {
            gravityData = GravityData(
                x = round(event.values[0] * 1e6f) / 1e6f,
                y = round(event.values[1] * 1e6f) / 1e6f,
                z = round(event.values[2] * 1e6f) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = gravityData
    
    override fun packData(data: Any): ByteArray {
        val gravityData = data as GravityData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(gravityData.x)
        dos.writeFloat(gravityData.y)
        dos.writeFloat(gravityData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        gravityData = GravityData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return gravityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = gravityData ?: return null
        return mapOf(
            "icon" to "arrow-down-thin-circle-outline",
            "name" to "Gravity",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class GravityData(val x: Float, val y: Float, val z: Float)
}

/**
 * Angular Velocity (Gyroscope) Sensor
 */
class AngularVelocitySensor(private val context: Context) : BaseSensor(SensorID.ANGULAR_VELOCITY, "angular_velocity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var angularVelocityData: AngularVelocityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        if (gyroscopeSensor != null) {
            sensorManager?.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        gyroscopeSensor = null
        angularVelocityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            angularVelocityData = AngularVelocityData(
                x = round(event.values[0] * 1e6f) / 1e6f,
                y = round(event.values[1] * 1e6f) / 1e6f,
                z = round(event.values[2] * 1e6f) / 1e6f
            )
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = angularVelocityData
    
    override fun packData(data: Any): ByteArray {
        val angularVelocityData = data as AngularVelocityData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeFloat(angularVelocityData.x)
        dos.writeFloat(angularVelocityData.y)
        dos.writeFloat(angularVelocityData.z)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val x = dis.readFloat()
        val y = dis.readFloat()
        val z = dis.readFloat()
        angularVelocityData = AngularVelocityData(x = x, y = y, z = z)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return angularVelocityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = angularVelocityData ?: return null
        return mapOf(
            "icon" to "orbit",
            "name" to "Angular Velocity",
            "values" to mapOf(
                "x" to data.x,
                "y" to data.y,
                "z" to data.z
            )
        )
    }
    
    data class AngularVelocityData(val x: Float, val y: Float, val z: Float)
}

/**
 * Fall event emitted when the 3-phase heuristic detects a fall + prolonged stillness.
 */
data class FallEvent(
    val detectedAtMs: Long,
    val peakImpactG: Float,
    val stillnessDurationMs: Long
)

/**
 * Acceleration Sensor — Tier 1 + Tier 2 power-aware fall detection.
 *
 * Power tiers:
 *   Tier 1 — TYPE_SIGNIFICANT_MOTION (hardware DSP, ~0.2 mA): tracks last-motion
 *             timestamp, cancels post-fall stillness confirmation if the person moves.
 *   Tier 2 — FIFO batching at 50 Hz with 1-second max report latency (~2 mA):
 *             CPU wakes once/second, processes a burst of ~50 samples, then sleeps.
 *
 * Fall state machine (runs on each batched sample):
 *   IDLE → FREEFALL  when |g| < 4.9 m/s² (0.5 g) for 80–400 ms
 *   FREEFALL → POST_IMPACT  when |g| > 24.5 m/s² (2.5 g) for ≥ 50 ms
 *   POST_IMPACT → CONFIRMING  when |g| ≈ 9.8 m/s² with low variance for ≥ 2 s
 *   CONFIRMING → FALL_CONFIRMED  after 45 s of continued stillness
 *
 * Observers should collect [fallEvents] and dispatch alerts.
 */
class AccelerationSensor(private val context: Context) :
    BaseSensor(SensorID.ACCELERATION, "acceleration", 1000L), SensorEventListener {

    companion object {
        private const val FREEFALL_THRESHOLD  = 4.9f   // m/s² (~0.5 g)
        private const val IMPACT_THRESHOLD    = 24.5f  // m/s² (~2.5 g)
        private const val FREEFALL_MIN_NS     =  80_000_000L  // 80 ms
        private const val FREEFALL_MAX_NS     = 400_000_000L  // 400 ms
        private const val IMPACT_MIN_NS       =  50_000_000L  // 50 ms
        private const val STILL_VARIANCE_MAX  = 0.8f   // m/s² std-dev → "still"
        private const val STILL_CONFIRM_MS    = 45_000L // confirm fall after 45 s still
        private const val BUFFER_SECONDS      = 10     // circular buffer length
        private const val SAMPLE_RATE_HZ      = 50
        private const val BUFFER_SIZE         = BUFFER_SECONDS * SAMPLE_RATE_HZ
        // FIFO batch latency — CPU wakes once per second instead of 50×/s
        private const val FIFO_LATENCY_US     = 1_000_000  // 1 second
    }

    // ── Hardware ─────────────────────────────────────────────────────────────
    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    // ── Fall state machine ────────────────────────────────────────────────────
    private enum class FallState { IDLE, FREEFALL, POST_IMPACT, CONFIRMING }
    @Volatile private var fallState = FallState.IDLE
    private var freefallStartNs = 0L
    private var impactStartNs   = 0L
    private var impactPeakG     = 0f
    private var stillStartMs    = 0L

    /** Timestamp of last TYPE_SIGNIFICANT_MOTION trigger (ms since epoch). */
    @Volatile var lastMotionAt: Long = System.currentTimeMillis()
        private set

    // Tier 1: TriggerEventListener (one-shot, re-registered on each trigger)
    private val smListener = object : android.hardware.TriggerEventListener() {
        override fun onTrigger(event: android.hardware.TriggerEvent) {
            lastMotionAt = System.currentTimeMillis()
            // Significant motion cancels post-fall stillness confirmation
            if (fallState == FallState.CONFIRMING || fallState == FallState.POST_IMPACT) {
                fallState = FallState.IDLE
            }
            // Re-register — TYPE_SIGNIFICANT_MOTION is one-shot
            significantMotionSensor?.let { sensorManager?.requestTriggerSensor(this, it) }
        }
    }

    // ── Circular buffer (Tier 2) ──────────────────────────────────────────────
    private val bufTimes = LongArray(BUFFER_SIZE)  // nanoseconds (event timestamp)
    private val bufMags  = FloatArray(BUFFER_SIZE) // |g| in m/s²
    private var bufHead  = 0
    private var bufCount = 0

    // ── Output ───────────────────────────────────────────────────────────────
    private val _fallEvents = kotlinx.coroutines.flow.MutableStateFlow<FallEvent?>(null)
    val fallEvents: kotlinx.coroutines.flow.StateFlow<FallEvent?> get() = _fallEvents

    private var accelerationData: AccelerationData? = null

    // ── BaseSensor lifecycle ──────────────────────────────────────────────────
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Tier 2: FIFO-batched 50 Hz — maxReportLatencyUs = 1 s → CPU wakes 1×/s not 50×/s
        accelerometerSensor?.let {
            sensorManager?.registerListener(
                this, it,
                20_000,          // samplingPeriodUs = 50 Hz
                FIFO_LATENCY_US  // maxReportLatencyUs = 1 s batch
            )
        }

        // Tier 1: hardware significant-motion (DSP, ~0.2 mA, one-shot)
        significantMotionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        significantMotionSensor?.let { sensorManager?.requestTriggerSensor(smListener, it) }

        lastMotionAt = System.currentTimeMillis()
        updateData()
    }

    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        significantMotionSensor?.let { sensorManager?.cancelTriggerSensor(smListener, it) }
        sensorManager = null
        accelerometerSensor = null
        significantMotionSensor = null
        fallState = FallState.IDLE
    }

    override fun updateData() { /* driven by onSensorChanged */ }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val mag = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Write to circular buffer
        bufTimes[bufHead] = e.timestamp
        bufMags[bufHead]  = mag
        bufHead = (bufHead + 1) % BUFFER_SIZE
        if (bufCount < BUFFER_SIZE) bufCount++

        // Update snapshot for pack()
        accelerationData = AccelerationData(x, y, z, mag)
        lastUpdate = System.currentTimeMillis()

        runFallMachine(e.timestamp, mag)
    }

    // ── Fall state machine ────────────────────────────────────────────────────
    private fun runFallMachine(tsNs: Long, mag: Float) {
        when (fallState) {
            FallState.IDLE -> {
                if (mag < FREEFALL_THRESHOLD) {
                    fallState = FallState.FREEFALL
                    freefallStartNs = tsNs
                }
            }
            FallState.FREEFALL -> {
                val elapsed = tsNs - freefallStartNs
                when {
                    mag >= FREEFALL_THRESHOLD && elapsed >= FREEFALL_MIN_NS -> {
                        // Low-g phase too short — not a fall
                        fallState = FallState.IDLE
                    }
                    elapsed > FREEFALL_MAX_NS -> {
                        // Low-g lasted too long — thrown/tossed, not a fall
                        fallState = FallState.IDLE
                    }
                    mag > IMPACT_THRESHOLD -> {
                        fallState    = FallState.POST_IMPACT
                        impactStartNs = tsNs
                        impactPeakG   = mag
                    }
                }
            }
            FallState.POST_IMPACT -> {
                if (mag > impactPeakG) impactPeakG = mag
                val elapsed = tsNs - impactStartNs
                if (elapsed >= IMPACT_MIN_NS) {
                    // Impact phase confirmed — now wait for stillness
                    fallState    = FallState.CONFIRMING
                    stillStartMs = System.currentTimeMillis()
                }
            }
            FallState.CONFIRMING -> {
                // Check recent variance — are we still?
                val variance = recentVariance(windowMs = 2000)
                if (variance > STILL_VARIANCE_MAX) {
                    // Person moved — cancel
                    fallState = FallState.IDLE
                    return
                }
                val stillMs = System.currentTimeMillis() - stillStartMs
                if (stillMs >= STILL_CONFIRM_MS) {
                    // 45 s of stillness after impact — emit fall event
                    _fallEvents.value = FallEvent(
                        detectedAtMs      = System.currentTimeMillis(),
                        peakImpactG       = impactPeakG / 9.8f,
                        stillnessDurationMs = stillMs
                    )
                    fallState = FallState.IDLE  // reset, don't re-fire
                }
            }
        }
    }

    /** Returns the magnitude variance over the last [windowMs] milliseconds of buffer. */
    private fun recentVariance(windowMs: Long): Float {
        if (bufCount < 2) return 0f
        val cutoffNs = (System.nanoTime() - windowMs * 1_000_000L)
        var sum = 0.0; var sum2 = 0.0; var n = 0
        // Walk buffer backwards (most recent first)
        var idx = (bufHead - 1 + BUFFER_SIZE) % BUFFER_SIZE
        repeat(bufCount) {
            if (bufTimes[idx] >= cutoffNs) {
                val v = bufMags[idx].toDouble()
                sum += v; sum2 += v * v; n++
            }
            idx = (idx - 1 + BUFFER_SIZE) % BUFFER_SIZE
        }
        if (n < 2) return 0f
        val mean = sum / n
        return ((sum2 / n) - mean * mean).toFloat().coerceAtLeast(0f)
    }

    // ── BaseSensor pack/unpack/render ─────────────────────────────────────────
    override fun getSensorData(): Any? = accelerationData

    override fun packData(data: Any): ByteArray {
        val d = data as AccelerationData
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.writeFloat(d.x); dos.writeFloat(d.y); dos.writeFloat(d.z)
        return output.toByteArray()
    }

    override fun unpackData(packed: ByteArray): Any {
        val dis = java.io.DataInputStream(packed.inputStream())
        val x = dis.readFloat(); val y = dis.readFloat(); val z = dis.readFloat()
        val mag = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
        accelerationData = AccelerationData(x, y, z, mag)
        synthesized = true; lastUpdate = System.currentTimeMillis()
        return accelerationData!!
    }

    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val d = accelerationData ?: return null
        return mapOf(
            "icon" to "arrow-right-thick",
            "name" to "Acceleration",
            "values" to mapOf("x" to d.x, "y" to d.y, "z" to d.z, "magnitude" to d.magnitude)
        )
    }

    data class AccelerationData(val x: Float, val y: Float, val z: Float, val magnitude: Float = 0f)
}

/**
 * Proximity Sensor
 */
class ProximitySensor(private val context: Context) : BaseSensor(SensorID.PROXIMITY, "proximity", 1000L),
    SensorEventListener {
    
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityData: ProximityData? = null
    
    override fun setupSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        
        if (proximitySensor != null) {
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateData()
    }
    
    override fun teardownSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        proximitySensor = null
        proximityData = null
    }
    
    override fun updateData() {
        // Data updated via onSensorChanged
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            proximityData = ProximityData(triggered = event.values[0] < event.sensor.maximumRange)
            lastUpdate = System.currentTimeMillis()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    override fun getSensorData(): Any? = proximityData
    
    override fun packData(data: Any): ByteArray {
        val proximityData = data as ProximityData
        return byteArrayOf(if (proximityData.triggered) 1 else 0)
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val triggered = packed[0] != 0.toByte()
        proximityData = ProximityData(triggered = triggered)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return proximityData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = proximityData ?: return null
        return mapOf(
            "icon" to "signal-distance-variant",
            "name" to "Proximity",
            "values" to mapOf("triggered" to data.triggered)
        )
    }
    
    data class ProximityData(val triggered: Boolean)
}




