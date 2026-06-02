package com.bitchat.android.telemetry.sensors

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.util.Log
import com.bitchat.android.telemetry.BaseSensor
import com.bitchat.android.telemetry.SensorID
import com.bitchat.android.telemetry.Telemeter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Packs device connectivity capabilities and status as a telemetry sensor.
 * Receiving peers can see what hardware/software each node has available.
 *
 * Encodes a compact bitmask of capabilities + key numeric values so mesh
 * peers know each other's strengths (e.g. "this node has GPS + BLE + camera"
 * or "this node is low battery at 12%"). Designed for AI-driven mesh
 * optimization: an agent can read the capability map across all peers and
 * decide which node should relay, which should conserve power, etc.
 */
class ConnectivitySensor(private val context: Context) :
    BaseSensor(SensorID.CONNECTIVITY, "connectivity", 30_000L) {

    private var snapshot: ConnectivitySnapshot? = null

    override fun setupSensor() { updateData() }
    override fun teardownSensor() { snapshot = null }
    override fun getSensorData(): Any? = snapshot

    override fun updateData() {
        try {
            snapshot = probe()
            lastUpdate = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e("ConnectivitySensor", "probe failed", e)
        }
    }

    private fun probe(): ConnectivitySnapshot {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val bleAdapter = bm?.adapter
        val bleOk = bleAdapter?.isEnabled == true
        val bleAdv = bleAdapter?.bluetoothLeAdvertiser != null

        val gpsOn = try { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true } catch (_: Exception) { false }
        val networkLocOn = try { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true } catch (_: Exception) { false }

        val wifiDirect = (context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) != null
        val wifiAware = Build.VERSION.SDK_INT >= 26 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

        val hasInternet = cm?.activeNetworkInfo?.isConnected == true

        val batteryIntent = context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val battLevel = batteryIntent?.let {
            val lev = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (sc > 0) (lev * 100) / sc else -1
        } ?: -1
        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val dozeExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus ?: -1 else -1

        val hasCameraRear = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasCameraFront = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

        val hasAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasBaro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val hasMag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val hasLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        val hasProximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

        val hasTTS = true // assume available; actual init tested separately
        val hasASR = SpeechRecognizer.isRecognitionAvailable(context)

        val hasTelephony = tm != null
        val simReady = tm?.simState == TelephonyManager.SIM_STATE_READY

        // Pack bitmask: 32 capability flags
        var caps = 0
        if (bleOk)         caps = caps or (1 shl 0)
        if (bleAdv)        caps = caps or (1 shl 1)
        if (gpsOn)         caps = caps or (1 shl 2)
        if (networkLocOn)  caps = caps or (1 shl 3)
        if (wifiDirect)    caps = caps or (1 shl 4)
        if (wifiAware)     caps = caps or (1 shl 5)
        if (hasInternet)   caps = caps or (1 shl 6)
        if (dozeExempt)    caps = caps or (1 shl 7)
        if (hasCameraRear) caps = caps or (1 shl 8)
        if (hasCameraFront) caps = caps or (1 shl 9)
        if (hasAccel)      caps = caps or (1 shl 10)
        if (hasGyro)       caps = caps or (1 shl 11)
        if (hasBaro)       caps = caps or (1 shl 12)
        if (hasMag)        caps = caps or (1 shl 13)
        if (hasLight)      caps = caps or (1 shl 14)
        if (hasProximity)  caps = caps or (1 shl 15)
        if (hasTTS)        caps = caps or (1 shl 16)
        if (hasASR)        caps = caps or (1 shl 17)
        if (hasTelephony)  caps = caps or (1 shl 18)
        if (simReady)      caps = caps or (1 shl 19)
        if (charging)      caps = caps or (1 shl 20)

        return ConnectivitySnapshot(
            capabilities = caps,
            batteryPercent = battLevel,
            thermalStatus = thermalStatus,
            apiLevel = Build.VERSION.SDK_INT,
            timestamp = System.currentTimeMillis() / 1000
        )
    }

    // ─── Pack / Unpack ───────────────────────────────────────────────

    override fun packData(data: Any): ByteArray {
        val s = data as ConnectivitySnapshot
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(s.capabilities)
        dos.writeByte(s.batteryPercent)
        dos.writeByte(s.thermalStatus)
        dos.writeByte(s.apiLevel)
        dos.writeLong(s.timestamp)
        return out.toByteArray()
    }

    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val caps = dis.readInt()
        val batt = dis.readByte().toInt() and 0xFF
        val thermal = dis.readByte().toInt()
        val api = dis.readByte().toInt() and 0xFF
        val ts = dis.readLong()
        snapshot = ConnectivitySnapshot(caps, batt, thermal, api, ts)
        lastUpdate = System.currentTimeMillis()
        return snapshot!!
    }

    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val s = snapshot ?: return null
        val active = mutableListOf<String>()
        val inactive = mutableListOf<String>()

        val labels = listOf(
            "BLE", "BLE-Adv", "GPS", "Net-Loc", "WiFi-Direct", "WiFi-Aware",
            "Internet", "Doze-Exempt", "Cam-Rear", "Cam-Front",
            "Accel", "Gyro", "Baro", "Mag", "Light", "Proximity",
            "TTS", "ASR", "Telephony", "SIM-Ready", "Charging"
        )
        for (i in labels.indices) {
            if ((s.capabilities and (1 shl i)) != 0) active.add(labels[i])
            else inactive.add(labels[i])
        }

        return mapOf(
            "icon" to "access-point-network",
            "name" to "Connectivity",
            "values" to mapOf(
                "capabilities" to active.joinToString(", "),
                "missing" to inactive.joinToString(", "),
                "battery" to "${s.batteryPercent}%",
                "thermal" to s.thermalStatus,
                "api" to s.apiLevel,
                "updated" to s.timestamp
            )
        )
    }

    data class ConnectivitySnapshot(
        val capabilities: Int,
        val batteryPercent: Int,
        val thermalStatus: Int,
        val apiLevel: Int,
        val timestamp: Long
    )
}
