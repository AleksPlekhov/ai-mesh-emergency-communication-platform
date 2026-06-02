package com.bitchat.android.mesh

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters

/**
 * TX power levels mapped to Android advertising constants.
 * Approximate dBm values are chipset-dependent; these are typical Qualcomm figures.
 */
enum class BleTxPower(
    val legacyLevel: Int,           // AdvertiseSettings.ADVERTISE_TX_POWER_*
    val extendedLevel: Int,         // AdvertisingSetParameters.TX_POWER_*
    val label: String,
    val dbmApprox: String,
    val hint: String
) {
    ULTRA_LOW(
        legacyLevel    = AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
        extendedLevel  = AdvertisingSetParameters.TX_POWER_ULTRA_LOW,
        label          = "Ultra Low",
        dbmApprox      = "≈ −21 dBm",
        hint           = "Same room / lab baseline"
    ),
    LOW(
        legacyLevel    = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
        extendedLevel  = AdvertisingSetParameters.TX_POWER_LOW,
        label          = "Low",
        dbmApprox      = "≈ −15 dBm",
        hint           = "Indoor short-range test (< 10 m)"
    ),
    MEDIUM(
        legacyLevel    = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
        extendedLevel  = AdvertisingSetParameters.TX_POWER_MEDIUM,
        label          = "Medium",
        dbmApprox      = "≈ −7 dBm",
        hint           = "Normal deployment, ~30 m LOS"
    ),
    HIGH(
        legacyLevel    = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
        extendedLevel  = AdvertisingSetParameters.TX_POWER_HIGH,
        label          = "High",
        dbmApprox      = "≈ +1 dBm",
        hint           = "Maximum range test — highest battery draw"
    );

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * Advertising interval — controls discovery speed and battery.
 * Faster = peer found sooner during a walk-away range test.
 */
enum class BleAdvertiseInterval(
    val legacyMode: Int,            // AdvertiseSettings.ADVERTISE_MODE_*
    val extendedInterval: Int,      // AdvertisingSetParameters.INTERVAL_*
    val label: String,
    val msApprox: String,
    val hint: String
) {
    FAST(
        legacyMode        = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
        extendedInterval  = AdvertisingSetParameters.INTERVAL_MIN,  // ~100 ms
        label             = "Fast",
        msApprox          = "~100 ms",
        hint              = "Best for active range walk — finds peers quickly"
    ),
    BALANCED(
        legacyMode        = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
        extendedInterval  = AdvertisingSetParameters.INTERVAL_MEDIUM, // ~250 ms
        label             = "Balanced",
        msApprox          = "~250 ms",
        hint              = "Normal mesh operation"
    ),
    SLOW(
        legacyMode        = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
        extendedInterval  = AdvertisingSetParameters.INTERVAL_HIGH,   // ~1000 ms
        label             = "Slow",
        msApprox          = "~1 s",
        hint              = "Background / power-saving mode"
    );

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/**
 * Full range-test configuration: PHY + TX power + advertising interval.
 * Passed as a unit through the UI → preference store → BLE managers.
 */
data class BleRangeTestConfig(
    val codec: BleCodec              = BleCodec.PHY_1M,
    val txPower: BleTxPower          = BleTxPower.MEDIUM,
    val interval: BleAdvertiseInterval = BleAdvertiseInterval.BALANCED
)
