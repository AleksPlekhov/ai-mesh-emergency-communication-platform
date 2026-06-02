package com.bitchat.android.mesh

import android.content.Context

/** Persists the user's BLE range-test configuration across app restarts. */
object BleCodecPreference {

    private const val PREFS    = "bitchat_ble_codec"
    private const val KEY_CODEC    = "codec"
    private const val KEY_TX_POWER = "tx_power"
    private const val KEY_INTERVAL = "interval"

    fun save(context: Context, config: BleRangeTestConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODEC,    config.codec.name)
            .putString(KEY_TX_POWER, config.txPower.name)
            .putString(KEY_INTERVAL, config.interval.name)
            .apply()
    }

    fun load(context: Context): BleRangeTestConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BleRangeTestConfig(
            codec    = BleCodec.fromString(p.getString(KEY_CODEC,    BleCodec.PHY_1M.name) ?: ""),
            txPower  = BleTxPower.fromName(p.getString(KEY_TX_POWER, BleTxPower.MEDIUM.name) ?: ""),
            interval = BleAdvertiseInterval.fromName(p.getString(KEY_INTERVAL, BleAdvertiseInterval.BALANCED.name) ?: "")
        )
    }
}
