package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    // Legacy advertising (1M PHY, API 21+)
    private var advertiseCallback: AdvertiseCallback? = null
    // Extended advertising (2M / Coded PHY, API 26+)
    private var advertisingSetCallback: AdvertisingSetCallback? = null

    // Active range-test config — call applyConfig() to change; restarts advertising automatically
    @Volatile private var currentConfig: BleRangeTestConfig = BleRangeTestConfig()
    private val currentCodec get() = currentConfig.codec

    // State management
    private var isActive = false

    /**
     * Apply a full range-test configuration (PHY + TX power + interval).
     * Restarts advertising immediately with the new parameters.
     * Existing GATT connections renegotiate PHY via [onPhyUpdate].
     */
    fun applyConfig(config: BleRangeTestConfig) {
        if (currentConfig == config) return
        Log.i(TAG, "BLE config → ${config.codec.label} / ${config.txPower.label} / ${config.interval.label}")
        currentConfig = config
        restartAdvertising()
    }

    /** Convenience for codec-only changes (e.g. from MeshBatteryCoordinator). */
    fun configureCodec(codec: BleCodec) {
        applyConfig(currentConfig.copy(codec = codec))
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        try {
            if (!com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value) {
                Log.i(TAG, "Server start skipped: GATT Server disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT server already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopAdvertising()
            // Ensure server is closed if present
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "GATT server stopped (already inactive)")
            return
        }

        isActive = false

        connectionScope.launch {
            stopAdvertising()
            
            // Try to cancel any active connections explicitly before closing
            try {
                // Disconnect ALL server connections
                val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
                servers.forEach { d ->
                    try { gattServer?.cancelConnection(d.device) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            // Close GATT server
            gattServer?.close()
            gattServer = null
            
            Log.i(TAG, "GATT server stopped")
        }
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring connection state change after shutdown")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")

                        // Express PHY preference; client will confirm via onPhyUpdate
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val codec = currentCodec
                            gattServer?.setPreferredPhy(device, codec.phyMask, codec.phyMask, codec.txOption)
                        }

                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)

                        connectionScope.launch {
                            delay(1000)
                            if (isActive) delegate?.onDeviceConnected(device)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        connectionTracker.cleanupDeviceConnection(device.address)
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device)
                    }
                }
            }
            
            override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val phyName = { phy: Int -> when(phy) { 1 -> "1M"; 2 -> "2M"; 3 -> "Coded"; else -> phy.toString() } }
                    Log.i(TAG, "Server: PHY agreed with ${device.address} → tx=${phyName(txPhy)} rx=${phyName(rxPhy)}")
                } else {
                    Log.w(TAG, "Server: PHY negotiation failed for ${device.address}, status=$status — staying on current PHY")
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring service added callback after shutdown")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring characteristic write after shutdown")
                    return
                }
                
                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring descriptor write after shutdown")
                    return
                }
                
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionTracker.addSubscribedDevice(device)

                    Log.d(TAG, "Server: Connection setup complete for ${device.address}")
                    connectionScope.launch {
                        delay(100)
                        if (isActive) { // Check if still active
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive) {
            Log.d(TAG, "Not starting advertising: manager not active")
            return
        }
        if (!enabled) {
            Log.i(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val cfg = currentConfig
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(cfg.interval.legacyMode)
            .setTxPowerLevel(cfg.txPower.legacyLevel)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
            
        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
        
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started (power mode: $mode) with stable ID: ${peerIDBytes.joinToString("") { "%02x".format(it) }}")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        
        // Use extended (AdvertisingSet) API for 2M / Coded PHY; fall back to legacy for 1M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && currentCodec != BleCodec.PHY_1M) {
            startExtendedAdvertising(data, scanResponse)
        } else {
            try {
                bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException starting advertising: ${se.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting advertising: ${e.message}")
            }
        }
    }

    /**
     * Extended advertising via AdvertisingSetParameters — required for 2M and Coded PHY.
     * Falls back silently to legacy advertising if the hardware doesn't support it.
     */
    @Suppress("MissingPermission")
    private fun startExtendedAdvertising(data: AdvertiseData, scanResponse: AdvertiseData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val codec = currentCodec
        val (primaryPhy, secondaryPhy) = when (codec) {
            BleCodec.PHY_2M    -> BluetoothDevice.PHY_LE_1M    to BluetoothDevice.PHY_LE_2M
            BleCodec.CODED_S2,
            BleCodec.CODED_S8  -> BluetoothDevice.PHY_LE_CODED to BluetoothDevice.PHY_LE_CODED
            else               -> BluetoothDevice.PHY_LE_1M    to BluetoothDevice.PHY_LE_1M
        }

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setPrimaryPhy(primaryPhy)
            .setSecondaryPhy(secondaryPhy)
            .setTxPowerLevel(codec.txOption.let { currentConfig.txPower.extendedLevel })
            .setInterval(currentConfig.interval.extendedInterval)
            .build()

        advertisingSetCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    Log.i(TAG, "Extended advertising started: ${codec.label}")
                } else {
                    Log.e(TAG, "Extended advertising failed (status=$status) — retrying with legacy 1M")
                    // Hardware doesn't support this PHY; fall back gracefully
                    connectionScope.launch {
                        stopAdvertising()
                        currentConfig = currentConfig.copy(codec = BleCodec.PHY_1M)
                        startAdvertising()
                    }
                }
            }
            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.d(TAG, "Extended advertising stopped")
            }
        }

        try {
            bleAdvertiser?.startAdvertisingSet(params, data, scanResponse, null, null, advertisingSetCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertisingSet failed: ${e.message} — falling back to legacy")
            advertisingSetCallback = null
            connectionScope.launch { startAdvertising() } // retry with 1M
        }
    }
    
    /**
     * Stop advertising — handles both legacy and extended advertising paths.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { bleAdvertiser.stopAdvertising(it) }
            advertiseCallback = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping legacy advertising: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                advertisingSetCallback?.let { bleAdvertiser.stopAdvertisingSet(it) }
                advertisingSetCallback = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping extended advertising: ${e.message}")
            }
        }
    }
    
    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }
}
