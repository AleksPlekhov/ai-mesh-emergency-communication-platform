package com.bitchat.android.radio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Emergency FM Repository
 *
 * Finds the nearest emergency FM stations to the user's current location.
 * Uses a one-shot BALANCED GPS fix: returns last known if <30 minutes old,
 * otherwise requests a fresh fix with a 10-second timeout, then falls back
 * to locale-based defaults.
 *
 * Distance is computed via the Haversine formula against 65 hardcoded
 * city center coordinates (see EmergencyFmStation.kt).
 */
class EmergencyFmRepository(private val context: Context) {

    companion object {
        private const val TAG = "EmergencyFmRepository"
        private const val LOCATION_FRESHNESS_MS = 30 * 60 * 1000L  // 30 minutes
        private const val LOCATION_TIMEOUT_MS = 10_000L             // 10 seconds
        private const val EARTH_RADIUS_KM = 6371.0
    }

    data class NearestResult(
        val station: EmergencyFmStation,
        val distanceKm: Double,
        val locationSource: LocationSource
    )

    enum class LocationSource {
        GPS_FRESH,       // Just obtained from FusedLocationProvider
        GPS_CACHED,      // Last known, <30 min old
        LOCALE_FALLBACK  // No GPS — guessed from system locale
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Find the [count] nearest emergency stations to the current position.
     * Suspends for up to 10 seconds waiting for a GPS fix if no fresh cache exists.
     */
    suspend fun findNearest(count: Int = 5): List<NearestResult> {
        val (lat, lon, source) = resolveLocation()
        return findNearestTo(lat, lon, source, count)
    }

    /**
     * Find nearest stations synchronously from a known lat/lon.
     * Used when the caller already has a location (e.g. from LocationService).
     */
    fun findNearestTo(
        lat: Double,
        lon: Double,
        source: LocationSource = LocationSource.GPS_FRESH,
        count: Int = 5
    ): List<NearestResult> {
        return EMERGENCY_FM_STATIONS
            .mapNotNull { station ->
                val cityKey = station.city.lowercase()
                val coords = CITY_COORDINATES[cityKey] ?: return@mapNotNull null
                val distKm = haversineKm(lat, lon, coords.first, coords.second)
                NearestResult(station, distKm, source)
            }
            .sortedBy { it.distanceKm }
            .take(count)
    }

    /**
     * Get all stations for a given region, sorted by city name then frequency.
     */
    fun stationsForRegion(region: EmergencyFmRegion): List<EmergencyFmStation> {
        return EMERGENCY_FM_STATIONS
            .filter { it.region == region }
            .sortedWith(compareBy({ it.city }, { it.frequencyMHz }))
    }

    /**
     * Synchronously find the single nearest station (for AI function call).
     * Returns null if no station can be found.
     */
    fun findNearestSync(lat: Double, lon: Double): EmergencyFmStation? {
        return findNearestTo(lat, lon, count = 1).firstOrNull()?.station
    }

    // ── Location resolution ───────────────────────────────────────────────────

    private suspend fun resolveLocation(): Triple<Double, Double, LocationSource> {
        if (!hasLocationPermission()) {
            Log.d(TAG, "No location permission — using locale fallback")
            return localeCoordinates()
        }

        // Try last known first
        val cached = lastKnownLocation()
        if (cached != null) {
            Log.d(TAG, "Using cached GPS location")
            return Triple(cached.first, cached.second, LocationSource.GPS_CACHED)
        }

        // Request a one-shot BALANCED fix
        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { requestOneShotLocation() }
        if (fresh != null) {
            Log.d(TAG, "Using fresh GPS fix")
            return Triple(fresh.first, fresh.second, LocationSource.GPS_FRESH)
        }

        Log.d(TAG, "GPS timeout — using locale fallback")
        return localeCoordinates()
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        var result: Pair<Double, Double>? = null

        try {
            @Suppress("MissingPermission")
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        val ageMs = System.currentTimeMillis() - loc.time
                        if (ageMs < LOCATION_FRESHNESS_MS) {
                            result = Pair(loc.latitude, loc.longitude)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Last known location failed: ${e.message}")
                }
            // Give the callback a moment to fire synchronously (it fires on main thread)
            Thread.sleep(200)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting last location: ${e.message}")
        }

        return result
    }

    private suspend fun requestOneShotLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        return suspendCancellableCoroutine { cont ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_TIMEOUT_MS
            ).setMaxUpdates(1).build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    client.removeLocationUpdates(this)
                    val loc = result.lastLocation
                    if (loc != null) {
                        cont.resume(Pair(loc.latitude, loc.longitude))
                    } else {
                        cont.resume(null)
                    }
                }
            }

            try {
                @Suppress("MissingPermission")
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                cont.invokeOnCancellation { client.removeLocationUpdates(callback) }
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception requesting location: ${e.message}")
                cont.resume(null)
            }
        }
    }

    // ── Locale fallback ───────────────────────────────────────────────────────

    private fun localeCoordinates(): Triple<Double, Double, LocationSource> {
        val locale = context.resources.configuration.locales[0]
        val country = locale.country.uppercase()

        // Default city per country
        val defaultCity = when (country) {
            "TR" -> "istanbul"
            "US" -> "new york"
            "GB" -> "london"
            "DE" -> "berlin"
            "JP" -> "tokyo"
            "AU" -> "sydney"
            "FR" -> "paris"
            else -> "new york"
        }

        val coords = CITY_COORDINATES[defaultCity] ?: Pair(40.7128, -74.0060)
        return Triple(coords.first, coords.second, LocationSource.LOCALE_FALLBACK)
    }

    // ── Haversine ─────────────────────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
