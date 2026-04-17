package com.handleit.transitpresence.data.gtfsrt

import com.google.transit.realtime.GtfsRealtime
import com.handleit.transitpresence.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GtfsRtClient
 *
 * Polls GTFS-Realtime feeds for:
 *  - VehiclePositions (where buses are right now)
 *  - TripUpdates (predicted arrival times)
 *
 * Exposes hot StateFlows consumed by the trip matching engine.
 * Gracefully degrades if feeds are unavailable.
 */
@Singleton
class GtfsRtClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val config: GtfsRtConfig,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public flows ──────────────────────────────────────────────────────────

    private val _vehiclePositions = MutableStateFlow<List<VehiclePosition>>(emptyList())
    val vehiclePositions: StateFlow<List<VehiclePosition>> = _vehiclePositions.asStateFlow()

    private val _tripUpdates = MutableStateFlow<List<TripUpdate>>(emptyList())
    val tripUpdates: StateFlow<List<TripUpdate>> = _tripUpdates.asStateFlow()

    private val _feedStatus = MutableStateFlow(FeedStatus.IDLE)
    val feedStatus: StateFlow<FeedStatus> = _feedStatus.asStateFlow()

    // ── Polling control ───────────────────────────────────────────────────────

    private var pollingJob: Job? = null

    fun startPolling(intervalMs: Long = config.pollIntervalMs) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            _feedStatus.value = FeedStatus.CONNECTING
            while (isActive) {
                fetchAll()
                delay(intervalMs)
            }
        }
        Timber.i("GTFS-RT: Polling started (interval=${intervalMs}ms)")
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _feedStatus.value = FeedStatus.IDLE
        Timber.i("GTFS-RT: Polling stopped")
    }

    // ── Fetch logic ───────────────────────────────────────────────────────────

    private suspend fun fetchAll() {
        val vpJob = scope.async { fetchVehiclePositions() }
        val tuJob = scope.async { fetchTripUpdates() }
        vpJob.await()
        tuJob.await()
    }

    private suspend fun fetchVehiclePositions() {
        try {
            val bytes = fetchProtoBytes(config.vehiclePositionsUrl) ?: return
            val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
            val positions = feed.entityList.mapNotNull { entity ->
                if (!entity.hasVehicle()) return@mapNotNull null
                val v = entity.vehicle
                if (!v.hasPosition()) return@mapNotNull null
                VehiclePosition(
                    vehicleId = v.vehicle?.id ?: entity.id,
                    tripId = v.trip?.tripId,
                    routeId = v.trip?.routeId,
                    lat = v.position.latitude,
                    lng = v.position.longitude,
                    bearing = if (v.position.hasBearing()) v.position.bearing else null,
                    speed = if (v.position.hasSpeed()) v.position.speed else null,
                    currentStopSequence = if (v.hasCurrentStopSequence()) v.currentStopSequence else null,
                    currentStatus = v.currentStatus.toModel(),
                    timestamp = v.timestamp,
                )
            }
            _vehiclePositions.value = positions
            _feedStatus.value = FeedStatus.LIVE
            Timber.v("GTFS-RT: Updated ${positions.size} vehicle positions")
        } catch (e: Exception) {
            Timber.w(e, "GTFS-RT: Failed to fetch vehicle positions")
            _feedStatus.value = FeedStatus.ERROR
        }
    }

    private suspend fun fetchTripUpdates() {
        try {
            val bytes = fetchProtoBytes(config.tripUpdatesUrl) ?: return
            val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
            val updates = feed.entityList.mapNotNull { entity ->
                if (!entity.hasTripUpdate()) return@mapNotNull null
                val tu = entity.tripUpdate
                TripUpdate(
                    tripId = tu.trip.tripId,
                    routeId = tu.trip.routeId,
                    vehicleId = tu.vehicle?.id,
                    stopTimeUpdates = tu.stopTimeUpdateList.map { stu ->
                        StopTimeUpdate(
                            stopId = stu.stopId,
                            stopSequence = if (stu.hasStopSequence()) stu.stopSequence else null,
                            arrivalDelay = if (stu.arrival.hasDelay()) stu.arrival.delay else null,
                            arrivalTime = if (stu.arrival.hasTime()) stu.arrival.time else null,
                            departureDelay = if (stu.departure.hasDelay()) stu.departure.delay else null,
                            departureTime = if (stu.departure.hasTime()) stu.departure.time else null,
                        )
                    },
                    timestamp = tu.timestamp,
                )
            }
            _tripUpdates.value = updates
            Timber.v("GTFS-RT: Updated ${updates.size} trip updates")
        } catch (e: Exception) {
            Timber.w(e, "GTFS-RT: Failed to fetch trip updates")
        }
    }

    private suspend fun fetchProtoBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("GTFS-RT: HTTP ${response.code} from $url")
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Timber.w(e, "GTFS-RT: Network error fetching $url")
            null
        }
    }

    // ── Arrival computation ───────────────────────────────────────────────────

    /**
     * Compute estimated arrival times for buses approaching a given stop.
     * Returns [BusArrival] list sorted by soonest arrival.
     */
    fun computeArrivalsForStop(
        stopId: String,
        routeId: String?,
        nowEpoch: Long = System.currentTimeMillis() / 1000,
    ): List<BusArrival> {
        val updates = _tripUpdates.value
        val vehicles = _vehiclePositions.value

        return updates.mapNotNull { tripUpdate ->
            if (routeId != null && tripUpdate.routeId != routeId) return@mapNotNull null

            val stopUpdate = tripUpdate.stopTimeUpdates.firstOrNull { it.stopId == stopId }
                ?: return@mapNotNull null

            val arrivalEpoch = stopUpdate.arrivalTime
                ?: (nowEpoch + (stopUpdate.arrivalDelay?.toLong() ?: 0L))

            val secsToArrival = arrivalEpoch - nowEpoch
            if (secsToArrival < -60) return@mapNotNull null // Already departed

            val vehicle = vehicles.firstOrNull { it.tripId == tripUpdate.tripId }

            BusArrival(
                routeId = tripUpdate.routeId ?: "",
                routeShortName = tripUpdate.routeId ?: "",
                tripId = tripUpdate.tripId,
                vehicleId = tripUpdate.vehicleId ?: vehicle?.vehicleId,
                headsign = "",
                predictedArrivalEpoch = arrivalEpoch,
                scheduledArrivalEpoch = null,
                isRealtime = true,
                secsToArrival = secsToArrival,
            )
        }.sortedBy { it.secsToArrival }
    }
}

// ─── Support types ────────────────────────────────────────────────────────────

enum class FeedStatus { IDLE, CONNECTING, LIVE, ERROR, DEGRADED }

data class GtfsRtConfig(
    val vehiclePositionsUrl: String,
    val tripUpdatesUrl: String,
    val pollIntervalMs: Long = 10_000L,
)

private fun GtfsRealtime.VehiclePosition.VehicleStopStatus.toModel(): VehicleStopStatus =
    when (this) {
        GtfsRealtime.VehiclePosition.VehicleStopStatus.INCOMING_AT -> VehicleStopStatus.INCOMING_AT
        GtfsRealtime.VehiclePosition.VehicleStopStatus.STOPPED_AT -> VehicleStopStatus.STOPPED_AT
        GtfsRealtime.VehiclePosition.VehicleStopStatus.IN_TRANSIT_TO -> VehicleStopStatus.IN_TRANSIT_TO
        else -> VehicleStopStatus.UNKNOWN
    }
