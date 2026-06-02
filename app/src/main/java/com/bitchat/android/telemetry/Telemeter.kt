package com.bitchat.android.telemetry

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.ContextCompat
import com.bitchat.android.telemetry.sensors.*

/**
 * Telemetry Commands
 */
object TelemetryCommands {
    const val PLUGIN_COMMAND = 0x00
    const val TELEMETRY_REQUEST = 0x01
    const val PING = 0x02
    const val ECHO = 0x03
    const val SIGNAL_REPORT = 0x04
}

/**
 * Sensor ID constants matching Python implementation
 */
object SensorID {
    const val NONE = 0x00
    const val TIME = 0x01
    const val LOCATION = 0x02
    const val PRESSURE = 0x03
    const val BATTERY = 0x04
    const val PHYSICAL_LINK = 0x05
    const val ACCELERATION = 0x06
    const val TEMPERATURE = 0x07
    const val HUMIDITY = 0x08
    const val MAGNETIC_FIELD = 0x09
    const val AMBIENT_LIGHT = 0x0A
    const val GRAVITY = 0x0B
    const val ANGULAR_VELOCITY = 0x0C
    const val PROXIMITY = 0x0E
    const val INFORMATION = 0x0F
    const val RECEIVED = 0x10
    const val POWER_CONSUMPTION = 0x11
    const val POWER_PRODUCTION = 0x12
    const val PROCESSOR = 0x13
    const val RAM = 0x14
    const val NVM = 0x15
    const val TANK = 0x16
    const val FUEL = 0x17
    const val RNS_TRANSPORT = 0x19
    const val LXMF_PROPAGATION = 0x18
    const val CONNECTION_MAP = 0x1A
    const val CONNECTIVITY = 0x1B
    const val CUSTOM = 0xFF
}

/**
 * Base Sensor interface
 */
interface TelemetrySensor {
    val sid: Int
    val name: String
    val staleTime: Long // milliseconds
    var active: Boolean
    var synthesized: Boolean
    var lastUpdate: Long
    var lastRead: Long
    
    fun start()
    fun stop()
    fun getData(): Any?
    fun pack(): ByteArray?
    fun unpack(packed: ByteArray?): Any?
    fun render(relativeTo: Telemeter? = null): Map<String, Any>?
}

/**
 * Base abstract sensor implementation
 */
abstract class BaseSensor(
    override val sid: Int,
    override val name: String,
    override val staleTime: Long = 5000L
) : TelemetrySensor {
    
    override var active: Boolean = false
    override var synthesized: Boolean = false
    override var lastUpdate: Long = 0
    override var lastRead: Long = 0
    
    private var _telemeter: Telemeter? = null
    protected val telemeter: Telemeter? get() = _telemeter
    
    fun setTelemeter(telemeter: Telemeter) {
        this._telemeter = telemeter
    }
    
    override fun getData(): Any? {
        val now = System.currentTimeMillis()
        if (!synthesized && staleTime > 0 && now > lastUpdate + staleTime) {
            updateData()
        }
        lastRead = now
        return getSensorData()
    }
    
    protected abstract fun getSensorData(): Any?
    protected abstract fun updateData()
    protected abstract fun setupSensor()
    protected abstract fun teardownSensor()
    
    override fun start() {
        if (!synthesized) {
            setupSensor()
        }
        active = true
    }
    
    override fun stop() {
        teardownSensor()
        active = false
    }
    
    override fun pack(): ByteArray? {
        val data = getData() ?: return null
        return packData(data)
    }
    
    override fun unpack(packed: ByteArray?): Any? {
        if (packed == null) return null
        return unpackData(packed)
    }
    
    protected abstract fun packData(data: Any): ByteArray
    protected abstract fun unpackData(packed: ByteArray): Any
}

/**
 * Telemeter - Main telemetry manager
 */
class Telemeter(
    private val context: Context,
    private val fromPacked: Boolean = false
) {
    
    companion object {
        private const val TAG = "Telemeter"
        
        fun fromPacked(context: Context, packed: ByteArray): Telemeter? {
            return try {
                val telemeter = Telemeter(context, fromPacked = true)
                val dis = java.io.DataInputStream(packed.inputStream())
                val count = dis.readInt()
                
                for (i in 0 until count) {
                    val sid = dis.readInt()
                    val dataSize = dis.readInt()
                    val data = ByteArray(dataSize)
                    dis.readFully(data)
                    
                    val name = telemeter.getName(sid)
                    if (name != null && name in telemeter.sensorRegistry) {
                        val sensor = telemeter.sensorRegistry[name]!!()
                        if (sensor is BaseSensor) {
                            sensor.setTelemeter(telemeter)
                        }
                        sensor.synthesized = true
                        sensor.active = true
                        sensor.unpack(data)
                        telemeter.sensorMap[name] = sensor
                    }
                }
                
                telemeter
            } catch (e: Exception) {
                Log.e(TAG, "Error unpacking telemetry", e)
                null
            }
        }
    }
    
    private val sensorMap = ConcurrentHashMap<String, TelemetrySensor>()
    private val sensorRegistry = createSensorRegistry()
    private val nameToSid = createNameToSidMap()
    private val sidToName = createSidToNameMap()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        if (!fromPacked) {
            enable("time")
        }
    }
    
    /**
     * Create sensor registry mapping names to sensor constructors
     */
    private fun createSensorRegistry(): Map<String, () -> TelemetrySensor> {
        return mapOf(
            "time" to { TimeSensor() },
            "information" to { InformationSensor() },
            "received" to { ReceivedSensor() },
            "battery" to { BatterySensor(context) },
            "pressure" to { PressureSensor(context) },
            "location" to { LocationSensor(context, this) },
            "physical_link" to { PhysicalLinkSensor() },
            "temperature" to { TemperatureSensor(context) },
            "humidity" to { HumiditySensor(context) },
            "magnetic_field" to { MagneticFieldSensor(context) },
            "ambient_light" to { AmbientLightSensor(context) },
            "gravity" to { GravitySensor(context) },
            "angular_velocity" to { AngularVelocitySensor(context) },
            "acceleration" to { AccelerationSensor(context) },
            "proximity" to { ProximitySensor(context) },
            "power_consumption" to { PowerConsumptionSensor() },
            "power_production" to { PowerProductionSensor() },
            "processor" to { ProcessorSensor() },
            "ram" to { RandomAccessMemorySensor() },
            "nvm" to { NonVolatileMemorySensor() },
            "custom" to { CustomSensor() },
            "tank" to { TankSensor() },
            "fuel" to { FuelSensor() },
            "rns_transport" to { RNSTransportSensor() },
            "lxmf_propagation" to { LXMFPropagationSensor() },
            "connection_map" to { ConnectionMapSensor() },
            "connectivity" to { ConnectivitySensor(context) }
        )
    }
    
    private fun createNameToSidMap(): Map<String, Int> {
        return mapOf(
            "time" to SensorID.TIME,
            "information" to SensorID.INFORMATION,
            "received" to SensorID.RECEIVED,
            "battery" to SensorID.BATTERY,
            "pressure" to SensorID.PRESSURE,
            "location" to SensorID.LOCATION,
            "physical_link" to SensorID.PHYSICAL_LINK,
            "temperature" to SensorID.TEMPERATURE,
            "humidity" to SensorID.HUMIDITY,
            "magnetic_field" to SensorID.MAGNETIC_FIELD,
            "ambient_light" to SensorID.AMBIENT_LIGHT,
            "gravity" to SensorID.GRAVITY,
            "angular_velocity" to SensorID.ANGULAR_VELOCITY,
            "acceleration" to SensorID.ACCELERATION,
            "proximity" to SensorID.PROXIMITY,
            "power_consumption" to SensorID.POWER_CONSUMPTION,
            "power_production" to SensorID.POWER_PRODUCTION,
            "processor" to SensorID.PROCESSOR,
            "ram" to SensorID.RAM,
            "nvm" to SensorID.NVM,
            "custom" to SensorID.CUSTOM,
            "tank" to SensorID.TANK,
            "fuel" to SensorID.FUEL,
            "rns_transport" to SensorID.RNS_TRANSPORT,
            "lxmf_propagation" to SensorID.LXMF_PROPAGATION,
            "connection_map" to SensorID.CONNECTION_MAP,
            "connectivity" to SensorID.CONNECTIVITY
        )
    }
    
    private fun createSidToNameMap(): Map<Int, String> {
        return nameToSid.map { it.value to it.key }.toMap()
    }
    
    fun getName(sid: Int): String? {
        return sidToName[sid]
    }
    
    fun synthesize(sensor: String) {
        if (sensor in sensorRegistry && !sensorMap.containsKey(sensor)) {
            val sensorInstance = sensorRegistry[sensor]!!()
            if (sensorInstance is BaseSensor) {
                sensorInstance.setTelemeter(this)
            }
            sensorInstance.active = true
            sensorInstance.synthesized = true
            sensorMap[sensor] = sensorInstance
        }
    }
    
    fun enable(sensor: String) {
        if (fromPacked) return
        
        if (sensor in sensorRegistry) {
            if (!sensorMap.containsKey(sensor)) {
                val sensorInstance = sensorRegistry[sensor]!!()
                if (sensorInstance is BaseSensor) {
                    sensorInstance.setTelemeter(this)
                }
                sensorMap[sensor] = sensorInstance
            }
            if (!sensorMap[sensor]!!.active) {
                sensorMap[sensor]!!.start()
            }
        }
    }
    
    fun disable(sensor: String) {
        if (fromPacked) return
        
        if (sensorMap.containsKey(sensor)) {
            val sensorInstance = sensorMap[sensor]!!
            if (sensorInstance.active) {
                sensorInstance.stop()
            }
            sensorMap.remove(sensor)
        }
    }
    
    fun stopAll() {
        if (fromPacked) return
        
        val sensors = sensorMap.keys.toList()
        for (sensor in sensors) {
            if (sensor != "time") {
                disable(sensor)
            }
        }
    }
    
    fun read(sensor: String): Any? {
        return if (fromPacked) {
            sensorMap[sensor]?.getData()
        } else {
            if (sensorMap.containsKey(sensor)) {
                sensorMap[sensor]!!.getData()
            } else {
                null
            }
        }
    }

    /** Returns the live sensor instance by name, or null if not enabled. */
    fun getSensor(name: String): TelemetrySensor? = sensorMap[name]
    
    fun readAll(): Map<String, Any?> {
        val readings = mutableMapOf<String, Any?>()
        for ((name, sensor) in sensorMap) {
            if (sensor.active) {
                readings[name] = if (fromPacked) {
                    sensor.getData()
                } else {
                    sensor.getData()
                }
            }
        }
        return readings
    }
    
    fun packed(): ByteArray {
        val packed = mutableMapOf<Int, ByteArray>()
        packed[SensorID.TIME] = packLong(System.currentTimeMillis() / 1000)
        
        for ((name, sensor) in sensorMap) {
            if (sensor.active) {
                val packedData = sensor.pack()
                if (packedData != null) {
                    val sid = nameToSid[name] ?: continue
                    packed[sid] = packedData
                }
            }
        }
        
        return packMap(packed)
    }
    
    fun render(relativeTo: Telemeter? = null): List<Map<String, Any>> {
        val rendered = mutableListOf<Map<String, Any>>()
        for ((_, sensor) in sensorMap) {
            if (sensor.active) {
                val renderResult = sensor.render(relativeTo)
                if (renderResult != null) {
                    rendered.add(renderResult)
                }
            }
        }
        return rendered
    }
    
    fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.$permission"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    fun release() {
        stopAll()
        coroutineScope.cancel()
    }
    
    // Helper methods for packing/unpacking
    private fun packLong(value: Long): ByteArray {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
    }
    
    private fun packMap(map: Map<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(map.size)
        for ((key, value) in map) {
            dos.writeInt(key)
            dos.writeInt(value.size)
            dos.write(value)
        }
        return output.toByteArray()
    }
    
}
