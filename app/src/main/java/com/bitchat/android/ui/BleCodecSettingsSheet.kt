package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mesh.BleAdvertiseInterval
import com.bitchat.android.mesh.BleCodec
import com.bitchat.android.mesh.BleRangeTestConfig
import com.bitchat.android.mesh.BleTxPower

/**
 * Bottom sheet for selecting BLE range-test parameters:
 *   • PHY / coding scheme  (1M | 2M | S=2 | S=8)
 *   • TX power             (Ultra Low | Low | Medium | High)
 *   • Advertising interval (Fast | Balanced | Slow)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleCodecSettingsSheet(
    currentConfig: BleRangeTestConfig = BleRangeTestConfig(),
    onConfigApplied: (BleRangeTestConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val codedPhySupported = BleCodec.isCodedPhySupported()

    var codec    by remember { mutableStateOf(currentConfig.codec) }
    var txPower  by remember { mutableStateOf(currentConfig.txPower) }
    var interval by remember { mutableStateOf(currentConfig.interval) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "BLE Range Test Parameters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (!codedPhySupported) SupportWarning()

            // ── PHY ──────────────────────────────────────────────────────────
            ParamSection(label = "PHY / Coding Scheme") {
                SegmentedRow(
                    items     = BleCodec.entries,
                    selected  = codec,
                    label     = { it.shortLabel },
                    sublabel  = { if (!codedPhySupported && (it == BleCodec.CODED_S2 || it == BleCodec.CODED_S8)) "BT5" else null },
                    enabled   = { codedPhySupported || (it != BleCodec.CODED_S2 && it != BleCodec.CODED_S8) },
                    onSelect  = { codec = it }
                )
                CodecDescriptionCard(codec)
            }

            // ── TX POWER ─────────────────────────────────────────────────────
            ParamSection(label = "TX Power") {
                SegmentedRow(
                    items    = BleTxPower.entries,
                    selected = txPower,
                    label    = { it.label },
                    sublabel = { it.dbmApprox },
                    enabled  = { true },
                    onSelect = { txPower = it }
                )
                HintCard(txPower.hint)
            }

            // ── ADVERTISING INTERVAL ─────────────────────────────────────────
            ParamSection(label = "Advertising Interval") {
                SegmentedRow(
                    items    = BleAdvertiseInterval.entries,
                    selected = interval,
                    label    = { it.label },
                    sublabel = { it.msApprox },
                    enabled  = { true },
                    onSelect = { interval = it }
                )
                HintCard(interval.hint)
            }

            // ── APPLY ─────────────────────────────────────────────────────────
            Button(
                onClick = {
                    onConfigApplied(BleRangeTestConfig(codec, txPower, interval))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply & Restart Advertising")
            }
        }
    }
}

// ── Section wrapper ───────────────────────────────────────────────────────────

@Composable
private fun ParamSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp
        )
        content()
    }
}

// ── Generic segmented row ─────────────────────────────────────────────────────

@Composable
private fun <T> SegmentedRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    sublabel: (T) -> String?,
    enabled: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        items.forEachIndexed { index, item ->
            val isSelected  = item == selected
            val isEnabled   = enabled(item)
            val segmentShape = when (index) {
                0            -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                items.size-1 -> RoundedCornerShape(topEnd   = 8.dp, bottomEnd   = 8.dp)
                else         -> RoundedCornerShape(0.dp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(segmentShape)
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            else       -> MaterialTheme.colorScheme.surface
                        }
                    )
                    .clickable(enabled = isEnabled) { onSelect(item) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label(item),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else       -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    sublabel(item)?.let { sub ->
                        Text(
                            text = sub,
                            fontSize = 9.sp,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                else       -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            if (index < items.size - 1) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

// ── Per-codec description ─────────────────────────────────────────────────────

@Composable
private fun CodecDescriptionCard(codec: BleCodec) {
    val isS8 = codec == BleCodec.CODED_S8
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isS8)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(codec.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (isS8) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("MAX RANGE", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Text(codec.description, fontSize = 12.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isS8) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(
                    "⚡ FEC eliminates retransmissions. At poor signal (< −85 dBm) battery draw is often lower than 1M PHY.",
                    fontSize = 11.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── Generic hint card ─────────────────────────────────────────────────────────

@Composable
private fun HintCard(hint: String) {
    Text(
        text = hint,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ── BT5 unavailable warning ───────────────────────────────────────────────────

@Composable
private fun SupportWarning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Coded PHY (S=2 / S=8) requires Android 8.0+ and a Bluetooth 5 chipset. " +
                   "Your device stays on 1M or 2M regardless of selection.",
            modifier = Modifier.padding(12.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
