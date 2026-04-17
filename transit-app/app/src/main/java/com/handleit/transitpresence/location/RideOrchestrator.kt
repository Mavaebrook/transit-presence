package com.handleit.transitpresence.location

import com.handleit.transitpresence.core.fsm.*
import com.handleit.transitpresence.core.fusion.BayesianFusionEngine
import com.handleit.transitpresence.core.model.*
import com.handleit.transitpresence.data.gtfs.*
import com.handleit.transitpresence.data.gtfsrt.GtfsRtClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RideOrchestrator
 *
 * The top-level coordinator. It:
 *  - Observes location, GTFS-RT, and sensor streams
 *  - Runs ETA threshold checks
 *  - Invokes the Sensor Fusion Engine
 *  - Fires RideEvents into the FSM engine
 *
 * This is the ONLY class that calls [RideFsmEngine.process].
 */
@Singleton
class RideOrchestrator @Inject constructor(
    private val fsmEngine: RideFsmEngine,
    private val fusionEngine: BayesianFusionEngine,
    private val locationModule: LocationModule,
    private val gtfsRtClient: GtfsRtClient,
    private val stopDao: StopDao,
    private val tripDao: TripDao,
    private val shapeDao: ShapeDao,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Expose state for ViewModel consumption
    val rideState: StateFlow<RideState> = fsmEngine.state

    // Current location context — updated by locationFlow collector
    private var lastLocation: LocationContext? = null
    private var accelBuffer = mutableListOf<Float>()
    private var wifiSsidConfidence: Float = -1f

    // Known transit Wi-Fi SSIDs for Central Florida (LYNX, SunRail, etc.)
    private val knownTransitSsids = setOf(
        "LYNX", "SunRail", "HART", "Transit", "PSTA"
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(prefs: UserPreferences) {
        startLocationCollection()
        startGtfsRtObservation(prefs)
        startWifiScanning(prefs)
        if (prefs.gtfsRtPollIntervalMs > 0) {
            gtfsRtClient.startPolling(prefs.gtfsRtPollIntervalMs)
        }
        Timber.i("Orchestrator: Started")
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        gtfsRtClient.stopPolling()
        Timber.i("Orchestrator: Stopped")
    }

    // ── Geofence callbacks (called by GeofenceBroadcastReceiver) ─────────────

    fun onGeofenceEntered(stopId: String) {
        scope.launch {
            val stop = stopDao.getById(stopId)?.toModel() ?: return@launch
            Timber.d("Orchestrator: Entered geofence for stop ${stop.stopName}")

            // Register the stop and wait for user to select a route
            // If we already have a route selected, move to WaitingAtStop immediately
            val currentState = fsmEngine.currentState
            if (currentState is RideState.Idle) {
                // Could auto-trigger if only one route serves this stop
                // For now, surface nearby stop to UI for user route selection
                _nearbyStop.value = stop
                updateGeofencedStopArrivals(stop)
            }
        }
    }

    fun onGeofenceExited(stopId: String) {
        val currentState = fsmEngine.currentState
        if (currentState is RideState.WaitingAtStop && currentState.stop.stopId == stopId) {
            fsmEngine.process(RideEvent.ExitedStopGeofence(currentState.stop))
        }
    }

    // ── Internal state for UI ─────────────────────────────────────────────────

    private val _nearbyStop = MutableStateFlow<Stop?>(null)
    val nearbyStop: StateFlow<Stop?> = _nearbyStop.asStateFlow()

    // ── Public user actions ───────────────────────────────────────────────────

    fun selectRoute(route: Route, stop: Stop, destinationStop: Stop?) {
        fsmEngine.process(RideEvent.RouteSelected(route, destinationStop))
        // Move to WaitingAtStop manually since RouteSelected needs context
        fsmEngine.process(RideEvent.EnteredStopGeofence(stop, 0f))
        scope.launch {
            val waitState = RideState.WaitingAtStop(
                stop = stop,
                route = route,
                arrivals = emptyList(),
            )
            // Directly set — this is the valid entry path after user action
            fsmEngine.process(RideEvent.BusArrivalsUpdated(
                gtfsRtClient.computeArrivalsForStop(stop.stopId, route.routeId)
            ))
        }
    }

    fun confirmBoarding() = fsmEngine.process(RideEvent.UserConfirmedBoarding)
    fun confirmExit() = fsmEngine.process(RideEvent.UserConfirmedExit)
    fun dismissTrip() = fsmEngine.process(RideEvent.TripDismissed)
    fun resetToIdle() = fsmEngine.process(RideEvent.ResetToIdle)

    // ── Location collection ───────────────────────────────────────────────────

    private fun startLocationCollection() {
        scope.launch {
            locationModule.locationFlow().collect { ctx ->
                lastLocation = ctx
                processLocationUpdate(ctx)
            }
        }
    }

    private suspend fun processLocationUpdate(ctx: LocationContext) {
        fsmEngine.process(RideEvent.LocationUpdated(ctx))

        val currentState = fsmEngine.currentState

        // Only run fusion when potentially on a bus
        if (currentState is RideState.BoardingWindow ||
            currentState is RideState.OnBus ||
            currentState is RideState.ApproachingExitStop ||
            currentState is RideState.ExitWindow
        ) {
            runFusion(ctx, currentState)
        }

        // Update geofenced stop arrivals if waiting
        if (currentState is RideState.WaitingAtStop) {
            updateGeofencedStopArrivals(currentState.stop)
        }

        // Track stop sequence progression if on bus
        if (currentState is RideState.OnBus) {
            checkStopSequenceProgress(ctx, currentState)
        }
    }

    private suspend fun runFusion(ctx: LocationContext, state: RideState) {
        // Get active trip candidate
        val tripCandidate = when (state) {
            is RideState.OnBus -> state.trip
            is RideState.BoardingWindow -> findTripCandidate(ctx, state.arrival.tripId, state.route)
            else -> null
        } ?: return

        // Compute route alignment
        val polyline = buildRoutePolyline(tripCandidate.trip.shapeId)
        val alignmentScore = polyline?.let {
            locationModule.computeRouteAlignment(ctx.latLng, it)
        } ?: 0f

        // Build signal bundle
        val bundle = SignalBundle(
            locationContext = ctx,
            nearestRouteAlignmentScore = alignmentScore,
            gtfsTripMatchConfidence = tripCandidate.gtfsTripMatchConfidence,
            gtfsTripMatched = tripCandidate.gtfsTripMatchConfidence > 0.7f,
            wifiSsidMatchConfidence = wifiSsidConfidence,
            vehicleMotionScore = if (accelBuffer.size >= 5)
                com.handleit.transitpresence.core.fusion.MotionClassifier.classify(accelBuffer.takeLast(20))
            else 0f,
        )

        val fusion = fusionEngine.compute(bundle)
        fsmEngine.process(RideEvent.FusionScoreUpdated(fusion))

        // If fusion meets threshold, update trip match
        if (fusion.meetsThreshold) {
            val updated = tripCandidate.copy(
                routeAlignmentScore = alignmentScore,
                gtfsTripMatchConfidence = fusion.onBusConfidence,
            )
            fsmEngine.process(RideEvent.TripMatchUpdated(updated))
        }
    }

    // ── GTFS-RT observation ───────────────────────────────────────────────────

    private fun startGtfsRtObservation(prefs: UserPreferences) {
        scope.launch {
            gtfsRtClient.tripUpdates.collect { _ ->
                val state = fsmEngine.currentState
                when (state) {
                    is RideState.WaitingAtStop -> updateGeofencedStopArrivals(state.stop)
                    is RideState.BusApproaching -> checkEtaThresholds(state)
                    is RideState.BoardingWindow -> checkEtaThresholds(state)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun updateGeofencedStopArrivals(stop: Stop) {
        val state = fsmEngine.currentState
        val routeId = when (state) {
            is RideState.WaitingAtStop -> state.route.routeId
            else -> null
        }

        val arrivals = gtfsRtClient.computeArrivalsForStop(stop.stopId, routeId)
        fsmEngine.process(RideEvent.BusArrivalsUpdated(arrivals))

        // Check ETA thresholds
        arrivals.firstOrNull()?.let { arrival ->
            for (threshold in EtaThreshold.entries.sortedByDescending { it.secsThreshold }) {
                if (arrival.secsToArrival <= threshold.secsThreshold) {
                    fsmEngine.process(RideEvent.EtaThresholdCrossed(arrival, arrival.secsToArrival, threshold))
                    break
                }
            }
        }
    }

    private fun checkEtaThresholds(state: RideState.BusApproaching) {
        val arrivals = gtfsRtClient.computeArrivalsForStop(state.stop.stopId, state.route.routeId)
        val arrival = arrivals.firstOrNull { it.tripId == state.arrival.tripId } ?: return
        for (threshold in EtaThreshold.entries.sortedByDescending { it.secsThreshold }) {
            if (arrival.secsToArrival <= threshold.secsThreshold) {
                fsmEngine.process(RideEvent.EtaThresholdCrossed(arrival, arrival.secsToArrival, threshold))
                break
            }
        }
    }

    private fun checkEtaThresholds(state: RideState.BoardingWindow) {
        val arrivals = gtfsRtClient.computeArrivalsForStop(state.stop.stopId, state.route.routeId)
        val arrival = arrivals.firstOrNull { it.tripId == state.arrival.tripId } ?: return
        if (arrival.secsToArrival <= EtaThreshold.T_MINUS_30SEC.secsThreshold) {
            fsmEngine.process(RideEvent.EtaThresholdCrossed(arrival, arrival.secsToArrival, EtaThreshold.T_MINUS_30SEC))
        }
    }

    // ── Stop sequence tracking ────────────────────────────────────────────────

    private suspend fun checkStopSequenceProgress(ctx: LocationContext, state: RideState.OnBus) {
        val nextStop = state.trip.nextStop ?: return
        val distToNext = RouteAlignmentEngine.haversineDistanceM(
            ctx.latLng,
            LatLng(nextStop.lat, nextStop.lng),
        )

        // If within 30m of next stop AND moving, consider it passed
        if (distToNext < 30.0 && ctx.speedMps > 1.0f) {
            val remainingStops = state.trip.remainingStops
            if (remainingStops.isEmpty()) {
                fsmEngine.process(RideEvent.ArrivedAtDestination(nextStop))
                return
            }

            val newNextStopId = remainingStops.firstOrNull()?.stopId
            val newNextStop = newNextStopId?.let { stopDao.getById(it)?.toModel() }

            if (newNextStop != null) {
                fsmEngine.process(RideEvent.StopSequenceAdvanced(newNextStop, remainingStops.size))
            }

            val stopsToDestination = remainingStops.size
            when {
                stopsToDestination <= 1 -> {
                    val dest = state.trip.destinationStop ?: return
                    fsmEngine.process(RideEvent.ApproachingDestination(stopsToDestination, dest))
                }
                stopsToDestination <= 3 -> {
                    val dest = state.trip.destinationStop ?: return
                    fsmEngine.process(RideEvent.ApproachingDestination(stopsToDestination, dest))
                }
            }
        }
    }

    // ── Wi-Fi scanning ────────────────────────────────────────────────────────

    private fun startWifiScanning(prefs: UserPreferences) {
        if (!prefs.wifiDetectionEnabled) {
            wifiSsidConfidence = -1f
            return
        }
        scope.launch {
            while (isActive) {
                wifiSsidConfidence = locationModule.scanWifiConfidence(knownTransitSsids)
                delay(15_000L)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun updateAccelSample(magnitudeMs2: Float) {
        if (accelBuffer.size > 50) accelBuffer.removeAt(0)
        accelBuffer.add(magnitudeMs2)
    }

    private suspend fun findTripCandidate(
        ctx: LocationContext,
        tripId: String,
        route: Route,
    ): TripCandidate? {
        val trip = tripDao.getById(tripId)?.toModel() ?: return null
        val vehiclePos = gtfsRtClient.vehiclePositions.value
            .firstOrNull { it.tripId == tripId }

        val gtfsConf = if (vehiclePos != null) {
            // Vehicle exists in RT feed — check proximity to user
            val vehicleDist = RouteAlignmentEngine.haversineDistanceM(
                ctx.latLng, LatLng(vehiclePos.lat.toDouble(), vehiclePos.lng.toDouble())
            )
            when {
                vehicleDist < 100 -> 0.95f
                vehicleDist < 300 -> 0.80f
                vehicleDist < 600 -> 0.60f
                else -> 0.30f
            }
        } else 0.0f

        return TripCandidate(
            trip = trip,
            route = route,
            vehiclePosition = vehiclePos,
            routeAlignmentScore = 0f,
            gtfsTripMatchConfidence = gtfsConf,
            remainingStops = emptyList(),
            nextStop = null,
            destinationStop = null,
        )
    }

    private suspend fun buildRoutePolyline(shapeId: String): RoutePolyline? {
        if (shapeId.isEmpty()) return null
        val points = shapeDao.getShape(shapeId).map { LatLng(it.lat, it.lng) }
        return if (points.isEmpty()) null else RoutePolyline(points)
    }
}
