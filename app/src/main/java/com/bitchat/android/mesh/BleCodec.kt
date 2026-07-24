package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.os.Build

/**
 * BLE PHY / coding-scheme selector.
 *
 * CODED_S8 note — counter-intuitive but correct:
 *   S=8 uses FEC (forward error correction) which eliminates retransmissions
 *   at weak signal levels. On a bad link 1M PHY retransmits 3-6× per packet;
 *   S=8 never retransmits. Net power draw at -90 dBm can be *lower* than 1M.
 *   Range: ~600 m LOS, ~4× wall/rubble penetration vs 1M.
 *
 * Requires API 26 (Android O). BT4 hardware silently stays on 1M.
 */
enum class BleCodec(
    val phyMask: Int,
    val txOption: Int,
    val label: String,
    val shortLabel: String,
    val description: String
) {
    PHY_1M(
        phyMask    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_LE_1M_MASK else 1,
        txOption   = 0,
        label      = "1M PHY",
        shortLabel = "1M",
        description = "Standard BLE. Best compatibility, ~30 m range indoors. " +
                      "Default for all BT4 and BT5 devices."
    ),
    PHY_2M(
        phyMask    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_LE_2M_MASK else 2,
        txOption   = 0,
        label      = "2M PHY",
        shortLabel = "2M",
        description = "Twice the throughput of 1M, same range. Best for high-bandwidth " +
                      "file transfer between nearby peers. Requires BT5."
    ),
    CODED_S2(
        phyMask    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_LE_CODED_MASK else 4,
        txOption   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_OPTION_S2 else 1,
        label      = "Coded S=2",
        shortLabel = "S=2",
        description = "FEC coding at 500 kbps. ~2× range of 1M with moderate power " +
                      "overhead. Good middle ground for urban rubble scenarios. BT5 only."
    ),
    CODED_S8(
        phyMask    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_LE_CODED_MASK else 4,
        txOption   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) BluetoothDevice.PHY_OPTION_S8 else 2,
        label      = "Coded S=8",
        shortLabel = "S=8",
        description = "FEC at 125 kbps — maximum range (~600 m LOS, 4× wall penetration). " +
                      "FEC eliminates retransmissions: at poor signal, power draw can be " +
                      "lower than 1M PHY. Recommended for disaster / rubble scenarios. BT5 only."
    );

    companion object {
        fun fromString(value: String): BleCodec =
            entries.firstOrNull { it.name == value } ?: PHY_1M

        /** True if the device can negotiate Coded PHY at runtime. */
        fun isCodedPhySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
