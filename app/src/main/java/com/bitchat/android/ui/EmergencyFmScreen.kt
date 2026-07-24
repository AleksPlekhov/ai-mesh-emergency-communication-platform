package com.bitchat.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.radio.EMERGENCY_FM_STATIONS
import com.bitchat.android.radio.EasMonitorService
import com.bitchat.android.radio.EmergencyFmRegion
import com.bitchat.android.radio.EmergencyFmRepository
import com.bitchat.android.radio.EmergencyFmStation
import com.bitchat.android.radio.FmHardwareController
import com.bitchat.android.ui.theme.SGColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Main Screen ───────────────────────────────────────────────────────────────

/**
 * EmergencyFmScreen
 *
 * Terminal-aesthetic FM radio tuner for disaster scenarios.
 * Displays nearest emergency broadcast stations and handles all 4 tuning layers.
 * Shows full-screen EAS alert when dual-tone signal is detected.
 *
 * @param controller FmHardwareController — created and owned by caller (e.g. ViewModel)
 * @param nearestStations Pre-resolved nearest stations (from EmergencyFmRepository)
 * @param locationSource How the location was resolved
 * @param locationCity Human-readable city name for the resolved location
 * @param onBack Navigation back callback
 */
@Composable
fun EmergencyFmScreen(
    controller: FmHardwareController,
    nearestStations: List<EmergencyFmRepository.NearestResult>,
    locationSource: EmergencyFmRepository.LocationSource?,
    locationCity: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fmState by controller.state.collectAsState()
    val easAlertState by EasMonitorService.alertState.collectAsState()

    // Track EAS chip visibility (user minimized the alert)
    var easMinimized by rememberSaveable { mutableStateOf(false) }
    var showEasFullScreen by remember { mutableStateOf(false) }

    // Region filter: null = nearest (distance-sorted), non-null = all stations in that region
    var selectedRegion by rememberSaveable { mutableStateOf<EmergencyFmRegion?>(null) }

    // System back follows the same path as the top-bar arrow
    BackHandler(onBack = onBack)

    val displayedStations = remember(selectedRegion, nearestStations, locationSource) {
        if (selectedRegion == null) {
            nearestStations
        } else {
            EMERGENCY_FM_STATIONS
                .filter { it.region == selectedRegion }
                .sortedWith(compareBy({ it.city }, { it.frequencyMHz }))
                .map { station ->
                    EmergencyFmRepository.NearestResult(
                        station = station,
                        distanceKm = 0.0,
                        locationSource = locationSource
                            ?: EmergencyFmRepository.LocationSource.LOCALE_FALLBACK
                    )
                }
        }
    }

    // Show full-screen alert when new alert arrives
    val currentAlert = easAlertState as? EasMonitorService.Companion.EasAlertState.AlertDetected
    if (currentAlert != null && !easMinimized) {
        showEasFullScreen = true
    }

    // Release FM hardware on composable dismiss
    DisposableEffect(Unit) {
        onDispose {
            // Only release hardware layers — OEM app and manual don't need cleanup
            val state = controller.state.value
            if (state is FmHardwareController.FmState.Tuned &&
                (state.layer == FmHardwareController.TuneLayer.JNI ||
                 state.layer == FmHardwareController.TuneLayer.RADIO_MANAGER)) {
                controller.release()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Main content ──────────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            FmTopBar(onBack = onBack)

            when (val state = fmState) {
                is FmHardwareController.FmState.Idle,
                is FmHardwareController.FmState.Connecting -> {
                    if (state is FmHardwareController.FmState.Connecting) {
                        ConnectingContent()
                    } else if (displayedStations.isEmpty()) {
                        EmptyStateContent(
                            context = context,
                            selectedRegion = selectedRegion,
                            onRegionSelected = { selectedRegion = it }
                        )
                    } else {
                        StationListContent(
                            nearestStations = displayedStations,
                            locationSource = locationSource,
                            locationCity = locationCity,
                            selectedRegion = selectedRegion,
                            onRegionSelected = { selectedRegion = it },
                            onTune = { result -> controller.tune(result.station) }
                        )
                    }
                }

                is FmHardwareController.FmState.Tuned -> {
                    TunedContent(
                        state = state,
                        easActive = currentAlert != null,
                        onMuteToggle = { controller.setMuted(!state.isMuted) },
                        onStop = { controller.release() }
                    )
                }

                is FmHardwareController.FmState.AppLaunched -> {
                    AppLaunchedContent(
                        state = state,
                        context = context,
                        onManual = { controller.tune(state.station) /* re-tune to fall to manual */ }
                    )
                }

                is FmHardwareController.FmState.ManualInstruction -> {
                    ManualContent(
                        state = state,
                        context = context
                    )
                }

                is FmHardwareController.FmState.Error -> {
                    ErrorContent(message = state.message, onRetry = onBack)
                }
            }
        }

        // ── EAS chip (persistent, top-right) ─────────────────────────────────
        if (currentAlert != null && easMinimized) {
            EasChip(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
                onClick = {
                    easMinimized = false
                    showEasFullScreen = true
                }
            )
        }

        // ── EAS full-screen alert ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = showEasFullScreen && currentAlert != null,
            enter = fadeIn(tween(200)) + slideInVertically { -it },
            exit = fadeOut(tween(150)) + slideOutVertically { -it }
        ) {
            if (currentAlert != null) {
                EasFullScreenAlert(
                    alert = currentAlert,
                    onMinimize = {
                        showEasFullScreen = false
                        easMinimized = true
                    }
                )
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun FmTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = SGColors.Safe
            )
        }
        Text(
            text = "EMERGENCY FM",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = SGColors.Safe,
            letterSpacing = 0.1.sp
        )
    }
    HorizontalDivider(color = SGColors.Safe.copy(alpha = 0.3f), thickness = 1.dp)
}

// ── Station list (Idle state) ─────────────────────────────────────────────────

@Composable
private fun StationListContent(
    nearestStations: List<EmergencyFmRepository.NearestResult>,
    locationSource: EmergencyFmRepository.LocationSource?,
    locationCity: String?,
    selectedRegion: EmergencyFmRegion?,
    onRegionSelected: (EmergencyFmRegion?) -> Unit,
    onTune: (EmergencyFmRepository.NearestResult) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Spacer(modifier = Modifier.height(12.dp)) }

        // Region filter row — always visible so users can switch regions
        item {
            RegionFilterRow(
                selectedRegion = selectedRegion,
                onRegionSelected = onRegionSelected
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Location line (hidden when a region is explicitly selected)
        if (selectedRegion == null && locationCity != null) {
            item {
                val isEstimated = locationSource == EmergencyFmRepository.LocationSource.LOCALE_FALLBACK
                val locationText = if (isEstimated) "Near $locationCity (estimated)" else locationCity
                Text(
                    text = locationText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = SGColors.MutedGrey,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        } else if (selectedRegion != null) {
            item {
                Text(
                    text = "All ${selectedRegion.label} stations (${nearestStations.size})",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = SGColors.MutedGrey,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        items(nearestStations) { result ->
            StationRow(result = result, onTune = { onTune(result) })
            HorizontalDivider(color = SGColors.Safe.copy(alpha = 0.15f), thickness = 0.5.dp)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun RegionFilterRow(
    selectedRegion: EmergencyFmRegion?,
    onRegionSelected: (EmergencyFmRegion?) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            RegionChip(
                label = "NEAREST",
                isSelected = selectedRegion == null,
                onClick = { onRegionSelected(null) }
            )
        }
        items(EmergencyFmRegion.values()) { region ->
            RegionChip(
                label = region.label,
                isSelected = selectedRegion == region,
                onClick = { onRegionSelected(region) }
            )
        }
    }
}

@Composable
private fun StationRow(
    result: EmergencyFmRepository.NearestResult,
    onTune: () -> Unit
) {
    val distText = when {
        result.distanceKm <= 0.0 -> ""
        result.distanceKm < 1000 -> "%.0f km".format(result.distanceKm)
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.station.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = SGColors.Safe,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.station.city} $distText",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = SGColors.MutedGrey
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "%.1f".format(result.station.frequencyMHz),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = SGColors.Safe,
            modifier = Modifier.semantics {
                contentDescription = "%.1f megahertz".format(result.station.frequencyMHz)
            }
        )
        Text(
            text = " MHz",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = SGColors.Safe.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Button(
            onClick = onTune,
            colors = ButtonDefaults.buttonColors(containerColor = SGColors.Accent),
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Text(
                text = "TUNE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

// ── Connecting ────────────────────────────────────────────────────────────────

@Composable
private fun ConnectingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = SGColors.Safe)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TUNING...",
                fontFamily = FontFamily.Monospace,
                color = SGColors.Safe,
                fontSize = 14.sp
            )
        }
    }
}

// ── Live Tuned state ──────────────────────────────────────────────────────────

@Composable
private fun TunedContent(
    state: FmHardwareController.FmState.Tuned,
    easActive: Boolean,
    onMuteToggle: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Station name
        Text(
            text = state.station.name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = SGColors.Safe
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Frequency — 72sp monospace, MHz at 50% alpha
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%.1f".format(state.station.frequencyMHz),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp,
                color = SGColors.Safe,
                modifier = Modifier.semantics {
                    contentDescription = "%.1f megahertz".format(state.station.frequencyMHz)
                }
            )
            Text(
                text = " MHz",
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                color = SGColors.Safe.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // City + distance / estimated note
        val isEstimated = state.locationSource == EmergencyFmRepository.LocationSource.LOCALE_FALLBACK
        val cityText = if (isEstimated) "Near ${state.station.city} (estimated)" else state.station.city
        Text(
            text = cityText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = SGColors.MutedGrey
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Signal strength bar
        SignalBar(dbm = state.signalStrengthDbm)

        Spacer(modifier = Modifier.height(8.dp))

        // Layer badge
        val layerText = when (state.layer) {
            FmHardwareController.TuneLayer.JNI -> "NATIVE HAL"
            FmHardwareController.TuneLayer.RADIO_MANAGER -> "RADIO API"
            else -> ""
        }
        if (layerText.isNotEmpty()) {
            Text(
                text = layerText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = SGColors.MutedGrey
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onMuteToggle,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (state.isMuted) SGColors.Warning else Color.Transparent,
                    contentColor = if (state.isMuted) Color.Black else SGColors.Warning
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SGColors.Warning)
            ) {
                Text(
                    text = if (state.isMuted) "UNMUTE" else "MUTE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = SGColors.Safe
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SGColors.Safe)
            ) {
                Text(
                    text = "STOP FM",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // EAS monitor status
        EasMonitorStatus(active = easActive)
    }
}

@Composable
private fun SignalBar(dbm: Int) {
    // Map dBm to 0–8 bar segments. FM: -100 dBm (weak) to -40 dBm (strong)
    val normalized = ((dbm + 100).coerceIn(0, 60) / 60f * 8).toInt()
    val barColor = when {
        normalized >= 6 -> SGColors.Safe
        normalized >= 3 -> SGColors.Warning
        else -> SGColors.Critical
    }
    val description = when {
        normalized >= 6 -> "Signal: strong"
        normalized >= 3 -> "Signal: moderate"
        else -> "Signal: weak"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        repeat(8) { i ->
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(16.dp)
                    .padding(end = 2.dp)
                    .background(
                        if (i < normalized) barColor else SGColors.MutedGrey.copy(alpha = 0.3f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$dbm dBm",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = SGColors.MutedGrey
        )
    }
}

@Composable
private fun EasMonitorStatus(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "eas_pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            contentDescription = if (active) "Emergency alert active, tap to expand"
            else "EAS monitor active"
        }
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(if (active) pulse else 1f)
                .background(SGColors.Safe, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (active) "EAS ALERT DETECTED" else "EAS MONITOR ACTIVE",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = SGColors.Safe
        )
    }
}

// ── OEM App Launched state ────────────────────────────────────────────────────

@Composable
private fun AppLaunchedContent(
    state: FmHardwareController.FmState.AppLaunched,
    context: Context,
    onManual: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = state.station.name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = SGColors.Safe
        )

        Spacer(modifier = Modifier.height(12.dp))

        FrequencyDisplay(frequencyMHz = state.station.frequencyMHz)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "FM app opened.\nTune to ${state.station.frequencyMHz} MHz.",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = SGColors.Safe,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val clip = ClipData.newPlainText("frequency", "${state.station.frequencyMHz} MHz")
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied: ${state.station.frequencyMHz} MHz", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = SGColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Text("COPY FREQUENCY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onManual,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SGColors.Safe),
            border = androidx.compose.foundation.BorderStroke(1.dp, SGColors.Safe)
        ) {
            Text("DIDN'T OPEN? SEE STEPS", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

// ── Manual instruction state ──────────────────────────────────────────────────

@Composable
private fun ManualContent(
    state: FmHardwareController.FmState.ManualInstruction,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        FrequencyDisplay(frequencyMHz = state.station.frequencyMHz)

        Spacer(modifier = Modifier.height(24.dp))

        val steps = listOf(
            "1. Open your FM radio app",
            "2. Tune to ${state.station.frequencyMHz} MHz",
            "3. Listen for emergency alerts"
        )
        steps.forEach { step ->
            Text(
                text = step,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = SGColors.Safe,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val clip = ClipData.newPlainText("frequency", "${state.station.frequencyMHz} MHz")
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied: ${state.station.frequencyMHz} MHz", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = SGColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Text("COPY FREQUENCY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

// ── Empty / no GPS state ──────────────────────────────────────────────────────

@Composable
private fun EmptyStateContent(
    context: Context,
    selectedRegion: EmergencyFmRegion?,
    onRegionSelected: (EmergencyFmRegion?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Set your location to find nearby emergency stations.",
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = SGColors.Safe,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                // Open location settings
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = SGColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Text("ENABLE LOCATION", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Spacer(modifier = Modifier.height(32.dp))

        HorizontalDivider(
            color = SGColors.MutedGrey.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "OR BROWSE BY REGION",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = SGColors.MutedGrey,
            letterSpacing = 0.05.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        RegionFilterRow(
            selectedRegion = selectedRegion,
            onRegionSelected = onRegionSelected
        )
    }
}

@Composable
private fun RegionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) SGColors.Accent else SGColors.Safe.copy(alpha = 0.6f)
    val bg = if (isSelected) SGColors.Accent.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (isSelected) SGColors.Accent else SGColors.Safe
    Box(
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(2.dp))
            .background(bg, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Region $label${if (isSelected) ", selected" else ""}" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "[$label]",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Error state ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ERROR",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = SGColors.Critical
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = SGColors.MutedGrey,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = SGColors.Accent),
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Text("GO BACK", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

// ── Shared: frequency display ─────────────────────────────────────────────────

@Composable
private fun FrequencyDisplay(frequencyMHz: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "%.1f".format(frequencyMHz),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 72.sp,
            color = SGColors.Safe,
            modifier = Modifier.semantics {
                contentDescription = "%.1f megahertz".format(frequencyMHz)
            }
        )
        Text(
            text = " MHz",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            color = SGColors.Safe.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

// ── EAS full-screen alert ─────────────────────────────────────────────────────

@Composable
private fun EasFullScreenAlert(
    alert: EasMonitorService.Companion.EasAlertState.AlertDetected,
    onMinimize: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val detectedTime = timeFormat.format(Date(alert.detectedAt))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SGColors.Critical),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "EMERGENCY ALERT",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Color.White,
                    letterSpacing = 0.05.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Emergency broadcast signal detected on this station. " +
                           "Stay tuned for official emergency instructions.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = Color.White,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Source + time metadata
                val sourceText = buildString {
                    if (alert.stationName.isNotBlank() && alert.stationName != "Unknown") {
                        append(alert.stationName)
                        if (alert.frequencyMHz > 0f) {
                            append(" · %.1f MHz".format(alert.frequencyMHz))
                        }
                        append(" · detected $detectedTime")
                    } else {
                        append("detected $detectedTime")
                    }
                }
                Text(
                    text = sourceText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            // MINIMIZE — white button, 56dp, centered
            Button(
                onClick = onMinimize,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = SGColors.Critical
                ),
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .height(56.dp)
                    .semantics { contentDescription = "Minimize alert, keep monitoring" },
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "MINIMIZE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

// ── EAS persistent chip ───────────────────────────────────────────────────────

@Composable
private fun EasChip(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "chip_pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "chip_alpha"
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .background(SGColors.Critical, RoundedCornerShape(4.dp))
            // Explicit 48dp minimum touch target (DESIGN.md requirement)
            .widthIn(min = 48.dp)
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "Emergency alert active, tap to expand" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "ALERT",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = Color.White,
            letterSpacing = 0.05.sp
        )
    }
}

// ── Entry-point wrapper ───────────────────────────────────────────────────────

/**
 * Self-contained wrapper that creates FmHardwareController and resolves stations
 * before handing off to EmergencyFmScreen. Call this from any composable.
 */
@Composable
fun EmergencyFmScreenWrapper(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val controller = remember { FmHardwareController(context) }
    var nearestStations by remember {
        mutableStateOf<List<EmergencyFmRepository.NearestResult>>(emptyList())
    }
    var locationSource by remember {
        mutableStateOf<EmergencyFmRepository.LocationSource?>(null)
    }
    var locationCity by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val repo = EmergencyFmRepository(context)
        val results = repo.findNearest(count = 5)
        nearestStations = results
        locationSource = results.firstOrNull()?.locationSource
        locationCity = results.firstOrNull()?.station?.city
    }

    EmergencyFmScreen(
        controller = controller,
        nearestStations = nearestStations,
        locationSource = locationSource,
        locationCity = locationCity,
        onBack = onDismiss
    )
}
