package com.handleit.transitpresence.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.handleit.transitpresence.core.fusion.MotionClassifier
import com.handleit.transitpresence.core.fusion.RouteAlignmentEngine
import com.handleit.transitpresence.core.model.*
import com.handleit.transitpresence.data.gtfs.StopDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stopDao: StopDao,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val geofencingClient: GeofencingClient,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        const val GEOFENCE_STOP_REQUEST_ID_PREFIX = "stop_"
        const val GEOFENCE_LOITERING_DELAY_MS = 10_000
        const val NEARBY_STOP_RADIUS_M = 500.0  // radius to search for stops to geofence
        const val DEFAULT_GEOFENCE_RADIUS_M = 50f
    }

    // ── Live location stream ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun locationFlow(
        fastIntervalMs: Long = 2_000L,
        slowIntervalMs: Long = 5_000L,
    ): Flow<LocationContext> = callbackFlow {
        if (!hasLocationPermission()) {
            Timber.w("Location permission not granted")
            close()
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, fastIntervalMs)
            .setMinUpdateIntervalMillis(fastIntervalMs)
            .setMaxUpdateDelayMillis(slowIntervalMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val ctx = LocationContext(
                        latLng = LatLng(location.latitude, location.longitude),
                        accuracyMeters = location.accuracy,
                        speedMps = location.speed,
                        bearingDeg = location.bearing,
                        timestampMs = location.time,
                        motionState = classifyMotion(location.speed),
                    )
                    trySend(ctx)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, context.mainLooper)
        Timber.i("Location: Updates started (interval=${fastIntervalMs}ms)")

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
            Timber.i("Location: Updates stopped")
        }
    }

    // ── Geofencing ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun registerStopGeofences(
        nearLat: Double,
        nearLng: Double,
        radiusM: Float = DEFAULT_GEOFENCE_RADIUS_M,
    ) {
        if (!hasLocationPermission()) return

        // Find stops within NEARBY_STOP_RADIUS_M and register geofences for them
        val nearbyStops = stopDao.getNearby(nearLat, nearLng, NEARBY_STOP_RADIUS_M, limit = 30)
        if (nearbyStops.isEmpty()) {
            Timber.d("Geofence: No stops found near ($nearLat, $nearLng)")
            return
        }

        val geofences = nearbyStops.map { stop ->
            Geofence.Builder()
                .setRequestId("$GEOFENCE_STOP_REQUEST_ID_PREFIX${stop.stopId}")
                .setCircularRegion(stop.lat, stop.lng, radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                    Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setLoiteringDelay(GEOFENCE_LOITERING_DELAY_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient.addGeofences(request, buildGeofencePendingIntent())
            .addOnSuccessListener {
                Timber.i("Geofence: Registered ${geofences.size} stop geofences")
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Geofence: Registration failed")
            }
    }

    fun clearGeofences() {
        geofencingClient.removeGeofences(buildGeofencePendingIntent())
            .addOnSuccessListener { Timber.i("Geofence: Cleared all geofences") }
    }

    private fun buildGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    // ── Route alignment ───────────────────────────────────────────────────────

    fun computeRouteAlignment(userPos: LatLng, polyline: RoutePolyline): Float =
        RouteAlignmentEngine.computeAlignment(userPos, polyline)

    // ── Wi-Fi SSID scan ───────────────────────────────────────────────────────

    /**
     * Returns confidence [0, 1] that a nearby Wi-Fi network is a known transit SSID.
     * Returns -1f if Wi-Fi scanning is unavailable or permission denied.
     */
    @SuppressLint("MissingPermission")
    fun scanWifiConfidence(knownTransitSsids: Set<String>): Float {
        // Android 13+ requires NEARBY_WIFI_DEVICES permission for scan results
        if (!hasWifiScanPermission()) return -1f

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return -1f

        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults
        if (results.isNullOrEmpty()) return 0f

        val matchedSsids = results.count { result ->
            knownTransitSsids.any { known ->
                result.SSID?.contains(known, ignoreCase = true) == true
            }
        }

        return (matchedSsids.toFloat() / results.size).coerceIn(0f, 1f)
            .let { if (matchedSsids > 0) maxOf(it, 0.7f) else it } // At least 0.7 if any match
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun classifyMotion(speedMps: Float): MotionState = when {
        speedMps < 0.3f -> MotionState.STATIONARY
        speedMps < 1.4f -> MotionState.WALKING
        speedMps >= 1.4f -> MotionState.VEHICLE_SPEED
        else -> MotionState.UNKNOWN
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasWifiScanPermission(): Boolean {
        // Android 13+ NEARBY_WIFI_DEVICES; fallback to ACCESS_FINE_LOCATION for older
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

// ─── Geofence Broadcast Receiver ─────────────────────────────────────────────

/**
 * Receives geofence transition broadcasts and routes them to the FSM via
 * the RideOrchestrator (injected via Hilt entry point).
 */
class GeofenceBroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            Timber.e("Geofence: Error ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        val stopIds = triggeringGeofences
            .mapNotNull { it.requestId.removePrefix(LocationModule.GEOFENCE_STOP_REQUEST_ID_PREFIX) }

        Timber.d("Geofence: transition=$transition stopIds=$stopIds")

        // Dispatch to RideOrchestrator via Hilt entry point
        val orchestrator = GeofenceEntryPoint.getOrchestrator(context)
        stopIds.forEach { stopId ->
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER ->
                    orchestrator.onGeofenceEntered(stopId)
                Geofence.GEOFENCE_TRANSITION_EXIT ->
                    orchestrator.onGeofenceExited(stopId)
            }
        }
    }
}

/**
 * Hilt entry point for injecting into the BroadcastReceiver.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface GeofenceEntryPoint {
    fun rideOrchestrator(): RideOrchestrator

    companion object {
        fun getOrchestrator(context: Context): RideOrchestrator =
            dagger.hilt.android.EntryPointAccessors
                .fromApplication(context, GeofenceEntryPoint::class.java)
                .rideOrchestrator()
    }
}
