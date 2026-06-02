package com.bitchat.android.telemetry.sensors

import android.util.Log
import com.bitchat.android.telemetry.BaseSensor
import com.bitchat.android.telemetry.SensorID
import com.bitchat.android.telemetry.Telemeter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Information Sensor
 */
class InformationSensor : BaseSensor(SensorID.INFORMATION, "information", 5000L) {
    
    private var contents: String = ""
    private var informationData: InformationData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        informationData = null
    }
    
    override fun updateData() {
        informationData = InformationData(contents = contents)
        lastUpdate = System.currentTimeMillis()
    }
    
    fun setContents(contents: String) {
        this.contents = contents
        updateData()
    }
    
    override fun getSensorData(): Any? = informationData
    
    override fun packData(data: Any): ByteArray {
        val informationData = data as InformationData
        return informationData.contents.toByteArray(Charsets.UTF_8)
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val contents = String(packed, Charsets.UTF_8)
        informationData = InformationData(contents = contents)
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return informationData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = informationData ?: return null
        return mapOf(
            "icon" to "information-variant",
            "name" to "Information",
            "values" to mapOf("contents" to data.contents)
        )
    }
    
    data class InformationData(val contents: String)
}

/**
 * Received Sensor
 */
class ReceivedSensor : BaseSensor(SensorID.RECEIVED, "received", 5000L) {
    
    private var by: String? = null
    private var via: String? = null
    private var geodesicDistance: Double? = null
    private var euclidianDistance: Double? = null
    private var receivedData: ReceivedData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        receivedData = null
    }
    
    fun setDistance(c1: LocationSensor.LocationData, c2: LocationSensor.LocationData) {
        euclidianDistance = calculateEuclidianDistance(c1, c2)
        geodesicDistance = calculateGeodesicDistance(c1, c2)
        updateData()
    }
    
    private fun calculateEuclidianDistance(c1: LocationSensor.LocationData, c2: LocationSensor.LocationData): Double {
        val dx = c1.latitude - c2.latitude
        val dy = c1.longitude - c2.longitude
        val dz = c1.altitude - c2.altitude
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    private fun calculateGeodesicDistance(c1: LocationSensor.LocationData, c2: LocationSensor.LocationData): Double {
        // Haversine formula
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(c2.latitude - c1.latitude)
        val dLon = Math.toRadians(c2.longitude - c1.longitude)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(c1.latitude)) * kotlin.math.cos(Math.toRadians(c2.latitude)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
    
    override fun updateData() {
        receivedData = ReceivedData(
            by = by,
            via = via,
            distance = DistanceData(
                geodesic = geodesicDistance,
                euclidian = euclidianDistance
            )
        )
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = receivedData
    
    override fun packData(data: Any): ByteArray {
        val receivedData = data as ReceivedData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeUTF(receivedData.by ?: "")
        dos.writeUTF(receivedData.via ?: "")
        dos.writeDouble(receivedData.distance.geodesic ?: 0.0)
        dos.writeDouble(receivedData.distance.euclidian ?: 0.0)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val by = dis.readUTF()
        val via = dis.readUTF()
        val geodesic = dis.readDouble()
        val euclidian = dis.readDouble()
        receivedData = ReceivedData(
            by = if (by.isEmpty()) null else by,
            via = if (via.isEmpty()) null else via,
            distance = DistanceData(
                geodesic = if (geodesic != 0.0) geodesic else null,
                euclidian = if (euclidian != 0.0) euclidian else null
            )
        )
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return receivedData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = receivedData ?: return null
        return mapOf(
            "icon" to "arrow-down-bold-hexagon-outline",
            "name" to "Received",
            "values" to mapOf(
                "by" to (data.by ?: ""),
                "via" to (data.via ?: ""),
                "distance" to mapOf(
                    "geodesic" to (data.distance.geodesic ?: 0.0),
                    "euclidian" to (data.distance.euclidian ?: 0.0)
                )
            )
        )
    }
    
    data class ReceivedData(
        val by: String?,
        val via: String?,
        val distance: DistanceData
    )
    
    data class DistanceData(
        val geodesic: Double?,
        val euclidian: Double?
    )
}

/**
 * Physical Link Sensor
 */
class PhysicalLinkSensor : BaseSensor(SensorID.PHYSICAL_LINK, "physical_link", 5000L) {
    
    private var rssi: Int? = null
    private var snr: Float? = null
    private var q: Float? = null
    private var physicalLinkData: PhysicalLinkData? = null
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        physicalLinkData = null
    }
    
    fun updateLink(rssi: Int?, snr: Float?, q: Float?) {
        this.rssi = rssi
        this.snr = snr
        this.q = q
        updateData()
    }
    
    override fun updateData() {
        physicalLinkData = PhysicalLinkData(
            rssi = rssi,
            snr = snr,
            q = q
        )
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = physicalLinkData
    
    override fun packData(data: Any): ByteArray {
        val physicalLinkData = data as PhysicalLinkData
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(physicalLinkData.rssi ?: Int.MIN_VALUE)
        dos.writeFloat(physicalLinkData.snr ?: Float.NaN)
        dos.writeFloat(physicalLinkData.q ?: Float.NaN)
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val rssi = dis.readInt()
        val snr = dis.readFloat()
        val q = dis.readFloat()
        physicalLinkData = PhysicalLinkData(
            rssi = if (rssi != Int.MIN_VALUE) rssi else null,
            snr = if (!snr.isNaN()) snr else null,
            q = if (!q.isNaN()) q else null
        )
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return physicalLinkData!!
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        val data = physicalLinkData ?: return null
        val q = data.q ?: 0f
        val icon = when {
            q > 90 -> "network-strength-4"
            q > 75 -> "network-strength-3"
            q > 40 -> "network-strength-2"
            q > 20 -> "network-strength-1"
            else -> "network-strength-outline"
        }
        
        return mapOf(
            "icon" to icon,
            "name" to "Physical Link",
            "values" to mapOf(
                "rssi" to (data.rssi ?: 0),
                "snr" to (data.snr ?: 0f),
                "q" to (data.q ?: 0f)
            )
        )
    }
    
    data class PhysicalLinkData(
        val rssi: Int?,
        val snr: Float?,
        val q: Float?
    )
}

/**
 * Power Consumption Sensor
 */
class PowerConsumptionSensor : BaseSensor(SensorID.POWER_CONSUMPTION, "power_consumption", 5000L) {
    
    private val consumers = ConcurrentHashMap<String, PowerConsumer>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        consumers.clear()
    }
    
    fun updateConsumer(power: Float, typeLabel: String? = null, customIcon: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        consumers[label] = PowerConsumer(power = power, customIcon = customIcon)
        updateData()
        return true
    }
    
    fun removeConsumer(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return consumers.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via consumers map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (consumers.isEmpty()) null else consumers
    
    override fun packData(data: Any): ByteArray {
        val consumers = data as Map<String, PowerConsumer>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(consumers.size)
        for ((label, consumer) in consumers) {
            dos.writeUTF(label)
            dos.writeFloat(consumer.power)
            dos.writeUTF(consumer.customIcon ?: "")
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        consumers.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val power = dis.readFloat()
            val customIcon = dis.readUTF()
            consumers[label] = PowerConsumer(
                power = power,
                customIcon = if (customIcon.isEmpty()) null else customIcon
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return consumers
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (consumers.isEmpty()) return null
        
        val consumerList = consumers.map { (label, consumer) ->
            mapOf(
                "label" to (if (label == "0x00") "Power consumption" else label),
                "w" to consumer.power,
                "custom_icon" to (consumer.customIcon ?: "")
            )
        }
        
        return mapOf(
            "icon" to "power-plug-outline",
            "name" to "Power Consumption",
            "values" to consumerList
        )
    }
    
    data class PowerConsumer(val power: Float, val customIcon: String?)
}

/**
 * Power Production Sensor
 */
class PowerProductionSensor : BaseSensor(SensorID.POWER_PRODUCTION, "power_production", 5000L) {
    
    private val producers = ConcurrentHashMap<String, PowerProducer>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        producers.clear()
    }
    
    fun updateProducer(power: Float, typeLabel: String? = null, customIcon: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        producers[label] = PowerProducer(power = power, customIcon = customIcon)
        updateData()
        return true
    }
    
    fun removeProducer(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return producers.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via producers map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (producers.isEmpty()) null else producers
    
    override fun packData(data: Any): ByteArray {
        val producers = data as Map<String, PowerProducer>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(producers.size)
        for ((label, producer) in producers) {
            dos.writeUTF(label)
            dos.writeFloat(producer.power)
            dos.writeUTF(producer.customIcon ?: "")
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        producers.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val power = dis.readFloat()
            val customIcon = dis.readUTF()
            producers[label] = PowerProducer(
                power = power,
                customIcon = if (customIcon.isEmpty()) null else customIcon
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return producers
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (producers.isEmpty()) return null
        
        val producerList = producers.map { (label, producer) ->
            mapOf(
                "label" to (if (label == "0x00") "Power Production" else label),
                "w" to producer.power,
                "custom_icon" to (producer.customIcon ?: "")
            )
        }
        
        return mapOf(
            "icon" to "lightning-bolt",
            "name" to "Power Production",
            "values" to producerList
        )
    }
    
    data class PowerProducer(val power: Float, val customIcon: String?)
}

/**
 * Processor Sensor
 */
class ProcessorSensor : BaseSensor(SensorID.PROCESSOR, "processor", 5000L) {
    
    private val entries = ConcurrentHashMap<String, ProcessorEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(
        currentLoad: Float = 0f,
        loadAvgs: Triple<Float, Float, Float>? = null,
        clock: Long? = null,
        typeLabel: String? = null
    ): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = ProcessorEntry(
            currentLoad = currentLoad,
            loadAvgs = loadAvgs,
            clock = clock
        )
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, ProcessorEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeFloat(entry.currentLoad)
            dos.writeFloat(entry.loadAvgs?.first ?: 0f)
            dos.writeFloat(entry.loadAvgs?.second ?: 0f)
            dos.writeFloat(entry.loadAvgs?.third ?: 0f)
            dos.writeLong(entry.clock ?: 0L)
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val currentLoad = dis.readFloat()
            val avg1 = dis.readFloat()
            val avg5 = dis.readFloat()
            val avg15 = dis.readFloat()
            val clock = dis.readLong()
            entries[label] = ProcessorEntry(
                currentLoad = currentLoad,
                loadAvgs = Triple(avg1, avg5, avg15),
                clock = if (clock != 0L) clock else null
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "Processor" else label),
                "current_load" to entry.currentLoad,
                "load_avgs" to listOf(
                    entry.loadAvgs?.first ?: 0f,
                    entry.loadAvgs?.second ?: 0f,
                    entry.loadAvgs?.third ?: 0f
                ),
                "clock" to (entry.clock ?: 0L)
            )
        }
        
        return mapOf(
            "icon" to "chip",
            "name" to "Processor",
            "values" to entryList
        )
    }
    
    data class ProcessorEntry(
        val currentLoad: Float,
        val loadAvgs: Triple<Float, Float, Float>?,
        val clock: Long?
    )
}

/**
 * Random Access Memory Sensor
 */
class RandomAccessMemorySensor : BaseSensor(SensorID.RAM, "ram", 5000L) {
    
    private val entries = ConcurrentHashMap<String, MemoryEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(capacity: Long = 0L, used: Long = 0L, typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = MemoryEntry(capacity = capacity, used = used)
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, MemoryEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeLong(entry.capacity)
            dos.writeLong(entry.used)
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val capacity = dis.readLong()
            val used = dis.readLong()
            entries[label] = MemoryEntry(capacity = capacity, used = used)
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "RAM" else label),
                "capacity" to entry.capacity,
                "used" to entry.used,
                "free" to (entry.capacity - entry.used),
                "percent" to ((entry.used.toFloat() / entry.capacity.toFloat()) * 100f)
            )
        }
        
        return mapOf(
            "icon" to "memory",
            "name" to "Random Access Memory",
            "values" to entryList
        )
    }
    
    data class MemoryEntry(val capacity: Long, val used: Long)
}

/**
 * Non-Volatile Memory Sensor
 */
class NonVolatileMemorySensor : BaseSensor(SensorID.NVM, "nvm", 5000L) {
    
    private val entries = ConcurrentHashMap<String, MemoryEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(capacity: Long = 0L, used: Long = 0L, typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = MemoryEntry(capacity = capacity, used = used)
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, MemoryEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeLong(entry.capacity)
            dos.writeLong(entry.used)
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val capacity = dis.readLong()
            val used = dis.readLong()
            entries[label] = MemoryEntry(capacity = capacity, used = used)
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "Storage" else label),
                "capacity" to entry.capacity,
                "used" to entry.used,
                "free" to (entry.capacity - entry.used),
                "percent" to ((entry.used.toFloat() / entry.capacity.toFloat()) * 100f)
            )
        }
        
        return mapOf(
            "icon" to "harddisk",
            "name" to "Non-Volatile Memory",
            "values" to entryList
        )
    }
    
    data class MemoryEntry(val capacity: Long, val used: Long)
}

/**
 * Custom Sensor
 */
class CustomSensor : BaseSensor(SensorID.CUSTOM, "custom", 5000L) {
    
    private val entries = ConcurrentHashMap<String, CustomEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(value: Any? = null, typeLabel: String? = null, customIcon: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = CustomEntry(value = value, customIcon = customIcon)
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, CustomEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeUTF(entry.value?.toString() ?: "")
            dos.writeUTF(entry.customIcon ?: "")
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val valueStr = dis.readUTF()
            val customIcon = dis.readUTF()
            entries[label] = CustomEntry(
                value = if (valueStr.isEmpty()) null else valueStr,
                customIcon = if (customIcon.isEmpty()) null else customIcon
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "Custom" else label),
                "value" to (entry.value ?: ""),
                "custom_icon" to (entry.customIcon ?: "")
            )
        }
        
        return mapOf(
            "icon" to "ruler",
            "name" to "Custom",
            "values" to entryList
        )
    }
    
    data class CustomEntry(val value: Any?, val customIcon: String?)
}

/**
 * Tank Sensor
 */
class TankSensor : BaseSensor(SensorID.TANK, "tank", 5000L) {
    
    private val entries = ConcurrentHashMap<String, TankEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(
        capacity: Float = 0f,
        level: Float = 0f,
        unit: String? = null,
        typeLabel: String? = null,
        customIcon: String? = null
    ): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = TankEntry(
            capacity = capacity,
            level = level,
            unit = unit ?: "L",
            customIcon = customIcon
        )
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, TankEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeFloat(entry.capacity)
            dos.writeFloat(entry.level)
            dos.writeUTF(entry.unit)
            dos.writeUTF(entry.customIcon ?: "")
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val capacity = dis.readFloat()
            val level = dis.readFloat()
            val unit = dis.readUTF()
            val customIcon = dis.readUTF()
            entries[label] = TankEntry(
                capacity = capacity,
                level = level,
                unit = unit,
                customIcon = if (customIcon.isEmpty()) null else customIcon
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "Tank" else label),
                "unit" to entry.unit,
                "capacity" to entry.capacity,
                "level" to entry.level,
                "free" to (entry.capacity - entry.level),
                "percent" to ((entry.level / entry.capacity) * 100f),
                "custom_icon" to (entry.customIcon ?: "")
            )
        }
        
        return mapOf(
            "icon" to "storage-tank",
            "name" to "Tank",
            "values" to entryList
        )
    }
    
    data class TankEntry(
        val capacity: Float,
        val level: Float,
        val unit: String,
        val customIcon: String?
    )
}

/**
 * Fuel Sensor
 */
class FuelSensor : BaseSensor(SensorID.FUEL, "fuel", 5000L) {
    
    private val entries = ConcurrentHashMap<String, FuelEntry>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        entries.clear()
    }
    
    fun updateEntry(
        capacity: Float = 0f,
        level: Float = 0f,
        unit: String? = null,
        typeLabel: String? = null,
        customIcon: String? = null
    ): Boolean {
        val label = typeLabel ?: "0x00"
        entries[label] = FuelEntry(
            capacity = capacity,
            level = level,
            unit = unit ?: "L",
            customIcon = customIcon
        )
        updateData()
        return true
    }
    
    fun removeEntry(typeLabel: String? = null): Boolean {
        val label = typeLabel ?: "0x00"
        return entries.remove(label) != null
    }
    
    override fun updateData() {
        // Data is managed via entries map
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (entries.isEmpty()) null else entries
    
    override fun packData(data: Any): ByteArray {
        val entries = data as Map<String, FuelEntry>
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        dos.writeInt(entries.size)
        for ((label, entry) in entries) {
            dos.writeUTF(label)
            dos.writeFloat(entry.capacity)
            dos.writeFloat(entry.level)
            dos.writeUTF(entry.unit)
            dos.writeUTF(entry.customIcon ?: "")
        }
        return output.toByteArray()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        val dis = DataInputStream(packed.inputStream())
        val count = dis.readInt()
        entries.clear()
        for (i in 0 until count) {
            val label = dis.readUTF()
            val capacity = dis.readFloat()
            val level = dis.readFloat()
            val unit = dis.readUTF()
            val customIcon = dis.readUTF()
            entries[label] = FuelEntry(
                capacity = capacity,
                level = level,
                unit = unit,
                customIcon = if (customIcon.isEmpty()) null else customIcon
            )
        }
        synthesized = true
        lastUpdate = System.currentTimeMillis()
        return entries
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (entries.isEmpty()) return null
        
        val entryList = entries.map { (label, entry) ->
            mapOf(
                "label" to (if (label == "0x00") "Fuel" else label),
                "unit" to entry.unit,
                "capacity" to entry.capacity,
                "level" to entry.level,
                "free" to (entry.capacity - entry.level),
                "percent" to ((entry.level / entry.capacity) * 100f),
                "custom_icon" to (entry.customIcon ?: "")
            )
        }
        
        return mapOf(
            "icon" to "fuel",
            "name" to "Fuel",
            "values" to entryList
        )
    }
    
    data class FuelEntry(
        val capacity: Float,
        val level: Float,
        val unit: String,
        val customIcon: String?
    )
}

/**
 * RNS Transport Sensor (placeholder - requires RNS integration)
 */
class RNSTransportSensor : BaseSensor(SensorID.RNS_TRANSPORT, "rns_transport", 60000L) {
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        // Cleanup
    }
    
    override fun updateData() {
        // TODO: Integrate with RNS transport statistics
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = null
    
    override fun packData(data: Any): ByteArray {
        return byteArrayOf()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        return Unit
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        return null
    }
}

/**
 * LXMF Propagation Sensor (placeholder - requires LXMF integration)
 */
class LXMFPropagationSensor : BaseSensor(SensorID.LXMF_PROPAGATION, "lxmf_propagation", 300000L) {
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        // Cleanup
    }
    
    override fun updateData() {
        // TODO: Integrate with LXMF propagation statistics
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = null
    
    override fun packData(data: Any): ByteArray {
        return byteArrayOf()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        return Unit
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        return null
    }
}

/**
 * Connection Map Sensor
 */
class ConnectionMapSensor : BaseSensor(SensorID.CONNECTION_MAP, "connection_map", 60000L) {
    
    private val maps = ConcurrentHashMap<String, ConnectionMap>()
    
    override fun setupSensor() {
        updateData()
    }
    
    override fun teardownSensor() {
        maps.clear()
    }
    
    fun ensureMap(mapName: String? = null): ConnectionMap {
        val name = mapName ?: "0x00"
        return maps.getOrPut(name) {
            ConnectionMap(name = name, points = ConcurrentHashMap())
        }
    }
    
    fun addPoint(
        lat: Double,
        lon: Double,
        altitude: Double? = null,
        typeLabel: String? = null,
        name: String? = null,
        mapName: String? = null,
        signalRssi: Int? = null,
        signalSnr: Float? = null,
        signalQ: Float? = null
    ) {
        val map = ensureMap(mapName)
        val point = ConnectionPoint(
            latitude = lat,
            longitude = lon,
            altitude = altitude,
            typeLabel = typeLabel,
            name = name,
            signal = SignalData(
                rssi = signalRssi,
                snr = signalSnr,
                q = signalQ
            )
        )
        // Generate hash for point
        val pointHash = point.hashCode().toString()
        map.points[pointHash] = point
    }
    
    override fun updateData() {
        // Data is managed via maps
        lastUpdate = System.currentTimeMillis()
    }
    
    override fun getSensorData(): Any? = if (maps.isEmpty()) null else maps
    
    override fun packData(data: Any): ByteArray {
        // Simplified packing - full implementation would serialize maps properly
        return byteArrayOf()
    }
    
    override fun unpackData(packed: ByteArray): Any {
        // Simplified unpacking
        return Unit
    }
    
    override fun render(relativeTo: Telemeter?): Map<String, Any>? {
        if (maps.isEmpty()) return null
        
        return mapOf(
            "icon" to "map-check-outline",
            "name" to "Connection Map",
            "values" to mapOf("maps" to maps)
        )
    }
    
    data class ConnectionMap(
        val name: String,
        val points: ConcurrentHashMap<String, ConnectionPoint>
    )
    
    data class ConnectionPoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val typeLabel: String?,
        val name: String?,
        val signal: SignalData
    )
    
    data class SignalData(
        val rssi: Int?,
        val snr: Float?,
        val q: Float?
    )
}
