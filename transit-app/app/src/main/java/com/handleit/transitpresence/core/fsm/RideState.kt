package com.handleit.transitpresence.core.fsm

import com.handleit.transitpresence.core.model.*

// ─── State ────────────────────────────────────────────────────────────────────

/**
 * The eight states of the Transit Presence state machine.
 * Each state carries only the data relevant to that state phase.
 * UI and Notification layers consume StateFlow<RideState>.
 */
sealed class RideState {

    /** No active trip. App is passively scanning for nearby stops. */
    object Idle : RideState()

    /** User is within geofence of a stop and has selected (or auto-matched) a route. */
    data class WaitingAtStop(
        val stop: Stop,
        val route: Route,
        val arrivals: List<BusArrival>,
        val enteredAt: Long = System.currentTimeMillis(),
    ) : RideState()

    /** A bus on the target route is approaching. ETA < T-5 min. */
    data class BusApproaching(
        val stop: Stop,
        val route: Route,
        val arrival: BusArrival,
        val secsToArrival: Long,
    ) : RideState()

    /** High-urgency boarding window. ETA < T-90 sec. Full attention UI. */
    data class BoardingWindow(
        val stop: Stop,
        val route: Route,
        val arrival: BusArrival,
        val secsToArrival: Long,
        val escalationLevel: EscalationLevel,
    ) : RideState()

    /** User is confirmed on the bus. Trip in progress. */
    data class OnBus(
        val trip: TripCandidate,
        val boardedAt: Long = System.currentTimeMillis(),
        val fusionResult: FusionResult,
    ) : RideState()

    /** 2–3 stops from destination. Prepare-to-exit alert active. */
    data class ApproachingExitStop(
        val trip: TripCandidate,
        val stopsRemaining: Int,
        val nextStop: Stop?,
        val destinationStop: Stop,
    ) : RideState()

    /** 1 stop from destination. Strong alert. Pull-cord prompt. */
    data class ExitWindow(
        val trip: TripCandidate,
        val destinationStop: Stop,
        val secsToArrival: Long?,
        val escalationLevel: EscalationLevel,
    ) : RideState()

    /** User has exited the bus. Trip complete. */
    data class TripComplete(
        val boardedStop: Stop?,
        val exitedStop: Stop?,
        val routeName: String,
        val durationMs: Long,
        val completedAt: Long = System.currentTimeMillis(),
    ) : RideState()
}

enum class EscalationLevel { PASSIVE, ACTIVE, STRONG, CRITICAL }

// ─── Events ───────────────────────────────────────────────────────────────────

/**
 * Events that drive state transitions. All inputs to the FSM engine are
 * expressed as RideEvents — deterministic and loggable.
 */
sealed class RideEvent {

    // Location events
    data class EnteredStopGeofence(val stop: Stop, val distanceMeters: Float) : RideEvent()
    data class ExitedStopGeofence(val stop: Stop) : RideEvent()
    data class LocationUpdated(val context: LocationContext) : RideEvent()

    // GTFS-RT events
    data class BusArrivalsUpdated(val arrivals: List<BusArrival>) : RideEvent()
    data class EtaThresholdCrossed(
        val arrival: BusArrival,
        val secsToArrival: Long,
        val threshold: EtaThreshold,
    ) : RideEvent()

    // Fusion events
    data class FusionScoreUpdated(val result: FusionResult) : RideEvent()
    data class TripMatchUpdated(val candidate: TripCandidate?) : RideEvent()

    // On-bus progress events
    data class StopSequenceAdvanced(
        val newNextStop: Stop,
        val stopsRemainingToDestination: Int,
    ) : RideEvent()
    data class ApproachingDestination(val stopsRemaining: Int, val destination: Stop) : RideEvent()
    data class ArrivedAtDestination(val stop: Stop) : RideEvent()

    // User actions
    data class RouteSelected(val route: Route, val destinationStop: Stop?) : RideEvent()
    object TripDismissed : RideEvent()
    object UserConfirmedBoarding : RideEvent()
    object UserConfirmedExit : RideEvent()
    object ResetToIdle : RideEvent()

    // System
    object GtfsRtFeedLost : RideEvent()
    object GtfsRtFeedRestored : RideEvent()
    object MockModeToggled : RideEvent()
}

enum class EtaThreshold(val secsThreshold: Long) {
    T_MINUS_5MIN(300L),
    T_MINUS_2MIN(120L),
    T_MINUS_90SEC(90L),
    T_MINUS_30SEC(30L),
}

// ─── Transition log entry ─────────────────────────────────────────────────────

data class StateTransitionLog(
    val fromState: String,
    val toState: String,
    val triggerEvent: String,
    val confidence: Float?,
    val timestampMs: Long = System.currentTimeMillis(),
    val notes: String = "",
)
