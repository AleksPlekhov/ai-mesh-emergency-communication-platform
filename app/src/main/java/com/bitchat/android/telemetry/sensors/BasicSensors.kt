package com.bitchat.android.telemetry.sensors

import android.content.Context
import com.bitchat.android.telemetry.BaseSensor
import com.bitchat.android.telemetry.SensorID
import com.bitchat.android.telemetry.Telemeter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.math.round

/**
 * Time Sensor
 */
class TimeSensor : BaseSensor(SensorID.TIME, "time", 100L) {
    
    private var timeData: TimeData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        timeData = null
    }
    
    override fun updateData() {
        timeData = TimeData(utc = System.currentTimeMillis() / 1000)
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = timeData
    
    override fun packData(data: Any): ByteArray {
        val timeData = data as TimeData
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timeData.utc).array()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val utc = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN).long
        timeData = TimeData(utc = utc)
        lastUpdate = System.currentTimeMillis()
        return timeData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = timeData ?: return null
        return mapOf(
            "icon" to "clock-time-ten-outline",
            "name" to "Timestamp",
            "values" to mapOf("UTC" to data.utc)
        )
    }
    
    data class TimeData(val utc: Long)
}

/**
 * Battery Sensor
 */
class BatterySensor(private val context: Context) : BaseSensor(SensorID.BATTERY, "battery", 10000L) {
    
    private var batteryData: BatteryData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        batteryData = null
    }
    
    override fun updateData() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val chargePercent = if (level >= 0 && scale > 0) {
                (level * 100 / scale).toFloat()
            } else {
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
            }
            
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            batteryData = BatteryData(
                chargePercent = (round(chargePercent * 10) / 10).toFloat(),
                charging = isCharging,
                temperature = null
            )
            lastUpdate = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("BatterySensor", "Error updating battery data", e)
            batteryData = null
        }
    }
    
    override fun getSensorData(): Any? = batteryData
    
    override fun packData(data: Any): ByteArray {
        val batteryData = data as BatteryData
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.writeFloat(batteryData.chargePercent)
        dos.writeBoolean(batteryData.charging)
        dos.writeFloat(batteryData.temperature ?: 0f)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = java.io.DataInputStream(packed.inputStream())
        val chargePercent = dis.readFloat()
        val charging = dis.readBoolean()
        val temperature = dis.readFloat()
        batteryData = BatteryData(
            chargePercent = chargePercent,
            charging = charging,
            temperature = if (temperature != 0f) temperature else null
        )
        lastUpdate = System.currentTimeMillis()
        return batteryData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = batteryData ?: return null
        val icon = when {
            data.charging -> when {
                data.chargePercent >= 90 -> "battery-charging-90"
                data.chargePercent >= 80 -> "battery-charging-80"
                data.chargePercent >= 70 -> "battery-charging-70"
                data.chargePercent >= 60 -> "battery-charging-60"
                data.chargePercent >= 50 -> "battery-charging-50"
                data.chargePercent >= 40 -> "battery-charging-40"
                data.chargePercent >= 30 -> "battery-charging-30"
                data.chargePercent >= 20 -> "battery-charging-20"
                data.chargePercent >= 10 -> "battery-charging-10"
                else -> "battery-outline"
            }
            else -> when {
                data.chargePercent >= 97 -> "battery"
                data.chargePercent >= 90 -> "battery-90"
                data.chargePercent >= 80 -> "battery-80"
                data.chargePercent >= 70 -> "battery-70"
                data.chargePercent >= 60 -> "battery-60"
                data.chargePercent >= 50 -> "battery-50"
                data.chargePercent >= 40 -> "battery-40"
                data.chargePercent >= 30 -> "battery-30"
                data.chargePercent >= 20 -> "battery-20"
                data.chargePercent >= 10 -> "battery-10"
                else -> "battery-outline"
            }
        }
        
        return mapOf(
            "icon" to icon,
            "name" to "Battery",
            "values" to mapOf(
                "percent" to data.chargePercent,
                "temperature" to data.temperature,
                "_meta" to if (data.charging) "charging" else "discharging"
            )
        )
    }
    
    data class BatteryData(
        val chargePercent: Float,
        val charging: Boolean,
        val temperature: Float?
    )
}

/**
 * Location Sensor — adaptive GPS polling with last-known caching.
 *
 * When the device hasn't moved more than [STATIONARY_DIST_M] metres in
 * [STATIONARY_TIME_MS] milliseconds the GPS is switched to a passive / low-power
 * mode (5-minute update interval + 50 m threshold) and the last-known fix is
 * served from cache.  This saves ~25 mA of continuous GPS drain.
 *
 * When movement is detected the sensor switches back to normal mode
 * (15-second / 4-metre updates) automatically.
 */
class LocationSensor(
    private val context: Context,
    private val locationTelemeter: Telemeter
) : BaseSensor(SensorID.LOCATION, "location", 15000L), LocationListener {

    companion object {
        private const val TAG = "LocationSensor"
        // Normal (moving) mode
        private const val MOVING_MIN_TIME_MS  = 15_000L  // 15 s
        private const val MOVING_MIN_DIST_M   = 4.0f     // 4 m
        // Stationary (cached) mode
        private const val STATIC_MIN_TIME_MS  = 300_000L // 5 min
        private const val STATIC_MIN_DIST_M   = 50.0f    // 50 m
        // Thresholds to decide "stationary"
        private const val STATIONARY_DIST_M   = 50.0f    // displacement < 50 m → stationary
        private const val STATIONARY_TIME_MS  = 5 * 60 * 1000L  // for 5 minutes
        private const val ACCURACY_TARGET     = 250.0f
    }

    private var locationManager: LocationManager? = null
    private var locationData: LocationData? = null
    private var lastRawLocation: Location? = null

    // Stationary tracking
    private var anchorLocation: Location? = null   // position at start of current still period
    private var anchorTimeMs:  Long = 0L
    private var isStationary = false

    override fun setupSensor() {
        if (!checkPermissions()) {
            Log.w(TAG, "Location permissions not granted")
            return
        }
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Seed with best last-known fix before requesting updates
        val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: (if (android.os.Build.VERSION.SDK_INT >= 31)
                    locationManager?.getLastKnownLocation(LocationManager.FUSED_PROVIDER) else null)
        if (lastKnown != null) lastRawLocation = lastKnown

        requestUpdates(stationary = false)
        anchorLocation = lastRawLocation
        anchorTimeMs   = System.currentTimeMillis()
        updateData()
    }

    override fun teardownSensor() {
        locationManager?.removeUpdates(this)
        locationManager = null
        locationData = null
        lastRawLocation = null
        anchorLocation = null
        isStationary = false
    }

    override fun updateData() {
        if (synthesized) { if (locationData != null) lastUpdate = System.currentTimeMillis(); return }
        val loc = lastRawLocation ?: return
        if (loc.accuracy > ACCURACY_TARGET) return
        locationData = LocationData(
            latitude   = round(loc.latitude  * 1e6) / 1e6,
            longitude  = round(loc.longitude * 1e6) / 1e6,
            altitude   = (round(loc.altitude * 100) / 100).toFloat(),
            speed      = (round(loc.speed * 3.6f * 100) / 100).toFloat(),
            bearing    = (round(loc.bearing * 100) / 100).toFloat(),
            accuracy   = (round(loc.accuracy * 100) / 100).toFloat(),
            lastUpdate = loc.time / 1000
        )
        lastUpdate = System.currentTimeMillis()
    }

    override fun getSensorData(): Any? = locationData

    // ── LocationListener ──────────────────────────────────────────────────────
    override fun onLocationChanged(location: Location) {
        lastRawLocation = location
        updateData()
        evaluateMotion(location)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    // ── Adaptive GPS ──────────────────────────────────────────────────────────

    /**
     * Compare current fix to the anchor.  If displacement < STATIONARY_DIST_M
     * for STATIONARY_TIME_MS → switch to slow-poll mode (saves ~25 mA).
     * If the person moves > STATIONARY_DIST_M → re-anchor and switch back.
     */
    private fun evaluateMotion(location: Location) {
        val anchor = anchorLocation
        if (anchor == null) {
            anchorLocation = location
            anchorTimeMs   = location.time
            return
        }

        val dist = anchor.distanceTo(location)

        if (dist > STATIONARY_DIST_M) {
            // Moved significantly — reset anchor, ensure fast updates
            anchorLocation = location
            anchorTimeMs   = location.time
            if (isStationary) {
                isStationary = false
                requestUpdates(stationary = false)
                Log.d(TAG, "GPS: movement detected (${dist.toInt()} m) → normal mode")
            }
        } else {
            val stillMs = location.time - anchorTimeMs
            if (!isStationary && stillMs >= STATIONARY_TIME_MS) {
                isStationary = true
                requestUpdates(stationary = true)
                Log.d(TAG, "GPS: stationary for ${stillMs / 60000} min → low-power mode")
            }
        }
    }

    private fun requestUpdates(stationary: Boolean) {
        locationManager?.removeUpdates(this)
        val provider = chooseBestProvider() ?: return
        val minTime = if (stationary) STATIC_MIN_TIME_MS else MOVING_MIN_TIME_MS
        val minDist = if (stationary) STATIC_MIN_DIST_M  else MOVING_MIN_DIST_M
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    locationManager?.requestLocationUpdates(provider, minTime, minDist, this)
                } catch (e: SecurityException) {
                    Log.e(TAG, "requestUpdates failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestUpdates outer failed", e)
        }
    }

    private fun chooseBestProvider(): String? {
        val criteria = android.location.Criteria().apply {
            accuracy = android.location.Criteria.ACCURACY_FINE
            isAltitudeRequired = true
        }
        return locationManager?.getBestProvider(criteria, true)
    }

    private fun checkPermissions(): Boolean =
        locationTelemeter.checkPermission("ACCESS_COARSE_LOCATION") &&
        locationTelemeter.checkPermission("ACCESS_FINE_LOCATION")

    // ── Pack / unpack ─────────────────────────────────────────────────────────
    override fun packData(data: Any): ByteArray {
        val d = data as LocationData
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.writeInt((d.latitude  * 1e6).toInt())
        dos.writeInt((d.longitude * 1e6).toInt())
        dos.writeInt((d.altitude  * 1e2).toInt())
        dos.writeInt((d.speed     * 1e2).toInt())
        dos.writeInt((d.bearing   * 1e2).toInt())
        dos.writeShort((d.accuracy * 1e2).toInt())
        dos.writeLong(d.lastUpdate)
        return output.toByteArray()
    }

    override fun unpackData(packed: ByteArray): Any {
        val dis = java.io.DataInputStream(packed.inputStream())
        locationData = LocationData(
            latitude   = dis.readInt() / 1e6,
            longitude  = dis.readInt() / 1e6,
            altitude   = (dis.readInt() / 1e2).toFloat(),
            speed      = (dis.readInt() / 1e2).toFloat(),
            bearing    = (dis.readInt() / 1e2).toFloat(),
            accuracy   = (dis.readShort() / 1e2).toFloat(),
            lastUpdate = dis.readLong()
        )
        synthesized = true; lastUpdate = System.currentTimeMillis()
        return locationData!!
    }

    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val d = locationData ?: return null
        val values = mutableMapOf<String, Any>(
            "latitude"  to d.latitude,  "longitude" to d.longitude,
            "altitude"  to d.altitude,  "speed"     to d.speed,
            "heading"   to d.bearing,   "accuracy"  to d.accuracy,
            "updated"   to d.lastUpdate,
            "mode"      to if (isStationary) "cached" else "live"
        )
        if (relativeTo != null) {
            val rel = relativeTo.read("location") as? LocationData
            if (rel != null) {
                val dist = calculateDistance(d.latitude, d.longitude, rel.latitude, rel.longitude)
                values["distance"] = mapOf("orthodromic" to dist, "euclidian" to dist)
            }
        }
        return mapOf("icon" to "map-marker", "name" to "Location", "values" to values)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat/2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon/2).pow(2)
        return R * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    data class LocationData(
        val latitude: Double, val longitude: Double,
        val altitude: Float,  val speed: Float,
        val bearing: Float,   val accuracy: Float,
        val lastUpdate: Long
    )
}
