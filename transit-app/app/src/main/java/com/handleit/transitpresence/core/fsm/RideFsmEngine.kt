package com.handleit.transitpresence.core.fsm

import com.handleit.transitpresence.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RideFsmEngine — the core state machine.
 *
 * Rules:
 *  - All transitions are explicit and logged.
 *  - Only valid transitions are allowed; invalid events in the wrong state are no-ops (logged).
 *  - The engine is the single source of truth for [RideState].
 *  - Callers feed [RideEvent]s; the engine decides whether a transition occurs.
 */
@Singleton
class RideFsmEngine @Inject constructor(
    private val transitionLogger: TransitionLogger,
) {
    private val _state = MutableStateFlow<RideState>(RideState.Idle)
    val state: StateFlow<RideState> = _state.asStateFlow()

    // Convenience accessor
    val currentState: RideState get() = _state.value

    // ── Public entry point ──────────────────────────────────────────────────

    fun process(event: RideEvent) {
        val from = currentState
        val to = transition(from, event)
        if (to != null && to !== from) {
            Timber.d("FSM: ${from::class.simpleName} → ${to::class.simpleName} via ${event::class.simpleName}")
            transitionLogger.log(
                StateTransitionLog(
                    fromState = from::class.simpleName ?: "?",
                    toState = to::class.simpleName ?: "?",
                    triggerEvent = event::class.simpleName ?: "?",
                    confidence = (event as? RideEvent.FusionScoreUpdated)?.result?.onBusConfidence,
                )
            )
            _state.value = to
        } else if (to == null) {
            Timber.v("FSM: ignored ${event::class.simpleName} in state ${from::class.simpleName}")
        }
    }

    // ── Transition table ─────────────────────────────────────────────────────

    private fun transition(state: RideState, event: RideEvent): RideState? = when (state) {

        // ── IDLE ──────────────────────────────────────────────────────────────
        is RideState.Idle -> when (event) {
            is RideEvent.EnteredStopGeofence -> null // Wait for route to be available
            is RideEvent.RouteSelected -> RideState.WaitingAtStop(
                stop = event.destinationStop?.let {
                    // Find the nearest stop — caller provides route; stop from geofence context needed
                    // In practice, this transition is triggered after a geofence event sets pending stop
                    it
                } ?: return@transition null,
                route = event.route,
                arrivals = emptyList(),
            )
            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── WAITING_AT_STOP ───────────────────────────────────────────────────
        is RideState.WaitingAtStop -> when (event) {
            is RideEvent.BusArrivalsUpdated -> state.copy(arrivals = event.arrivals)

            is RideEvent.EtaThresholdCrossed -> when (event.threshold) {
                EtaThreshold.T_MINUS_5MIN, EtaThreshold.T_MINUS_2MIN ->
                    RideState.BusApproaching(
                        stop = state.stop,
                        route = state.route,
                        arrival = event.arrival,
                        secsToArrival = event.secsToArrival,
                    )
                EtaThreshold.T_MINUS_90SEC, EtaThreshold.T_MINUS_30SEC ->
                    RideState.BoardingWindow(
                        stop = state.stop,
                        route = state.route,
                        arrival = event.arrival,
                        secsToArrival = event.secsToArrival,
                        escalationLevel = EscalationLevel.STRONG,
                    )
            }

            is RideEvent.ExitedStopGeofence -> RideState.Idle
            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── BUS_APPROACHING ───────────────────────────────────────────────────
        is RideState.BusApproaching -> when (event) {
            is RideEvent.EtaThresholdCrossed -> when (event.threshold) {
                EtaThreshold.T_MINUS_2MIN -> state.copy(
                    secsToArrival = event.secsToArrival,
                )
                EtaThreshold.T_MINUS_90SEC, EtaThreshold.T_MINUS_30SEC ->
                    RideState.BoardingWindow(
                        stop = state.stop,
                        route = state.route,
                        arrival = state.arrival,
                        secsToArrival = event.secsToArrival,
                        escalationLevel = if (event.threshold == EtaThreshold.T_MINUS_30SEC)
                            EscalationLevel.CRITICAL else EscalationLevel.STRONG,
                    )
                else -> state.copy(secsToArrival = event.secsToArrival)
            }

            is RideEvent.FusionScoreUpdated ->
                if (event.result.meetsThreshold && state.arrival.tripId.isNotEmpty()) {
                    // Bus arrived and user is on it — fast-path boarding
                    null // wait for TripMatchUpdated
                } else null

            is RideEvent.TripMatchUpdated -> {
                val candidate = event.candidate ?: return@transition null
                if (candidate.gtfsTripMatchConfidence > 0.85f) {
                    // Skipped BOARDING_WINDOW — user already boarded
                    RideState.OnBus(trip = candidate, fusionResult = FusionResult(
                        onBusConfidence = candidate.gtfsTripMatchConfidence,
                        dominantSignal = "gtfs_trip_match",
                        signalBreakdown = mapOf("gtfs_trip" to candidate.gtfsTripMatchConfidence),
                        meetsThreshold = true,
                    ))
                } else null
            }

            is RideEvent.BusArrivalsUpdated -> state.copy(
                secsToArrival = event.arrivals
                    .firstOrNull { it.tripId == state.arrival.tripId }
                    ?.secsToArrival ?: state.secsToArrival
            )

            is RideEvent.ExitedStopGeofence -> RideState.Idle
            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── BOARDING_WINDOW ───────────────────────────────────────────────────
        is RideState.BoardingWindow -> when (event) {
            is RideEvent.FusionScoreUpdated -> {
                if (event.result.meetsThreshold) null // wait for TripMatchUpdated
                else state // Update escalation only
            }

            is RideEvent.TripMatchUpdated -> {
                val candidate = event.candidate ?: return@transition null
                if (candidate.gtfsTripMatchConfidence > 0.85f ||
                    candidate.routeAlignmentScore > 0.80f) {
                    RideState.OnBus(trip = candidate, fusionResult = FusionResult(
                        onBusConfidence = candidate.gtfsTripMatchConfidence.coerceAtLeast(
                            candidate.routeAlignmentScore),
                        dominantSignal = if (candidate.gtfsTripMatchConfidence > candidate.routeAlignmentScore)
                            "gtfs_trip_match" else "route_alignment",
                        signalBreakdown = mapOf(
                            "gtfs_trip" to candidate.gtfsTripMatchConfidence,
                            "route_alignment" to candidate.routeAlignmentScore,
                        ),
                        meetsThreshold = true,
                    ))
                } else null
            }

            is RideEvent.UserConfirmedBoarding -> {
                // Manual override — user tapped "I'm on the bus"
                val dummyFusion = FusionResult(
                    onBusConfidence = 1.0f,
                    dominantSignal = "user_confirmed",
                    signalBreakdown = mapOf("user_override" to 1.0f),
                    meetsThreshold = true,
                )
                // Need a TripCandidate — this case means we need to keep a pending candidate
                // in the engine context. For simplicity, emit null and let orchestrator handle.
                null
            }

            is RideEvent.EtaThresholdCrossed -> state.copy(
                secsToArrival = event.secsToArrival,
                escalationLevel = when (event.threshold) {
                    EtaThreshold.T_MINUS_30SEC -> EscalationLevel.CRITICAL
                    else -> state.escalationLevel
                }
            )

            // Bus left without boarding
            is RideEvent.BusArrivalsUpdated -> {
                val stillArriving = event.arrivals.any {
                    it.tripId == state.arrival.tripId && it.secsToArrival > -30
                }
                if (!stillArriving) {
                    Timber.w("FSM: Bus ${state.arrival.tripId} departed without boarding — returning to WaitingAtStop")
                    RideState.WaitingAtStop(
                        stop = state.stop,
                        route = state.route,
                        arrivals = event.arrivals,
                    )
                } else null
            }

            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── ON_BUS ────────────────────────────────────────────────────────────
        is RideState.OnBus -> when (event) {
            is RideEvent.TripMatchUpdated ->
                state.copy(trip = event.candidate ?: state.trip)

            is RideEvent.StopSequenceAdvanced ->
                state.copy(trip = state.trip.copy(nextStop = event.newNextStop))

            is RideEvent.ApproachingDestination -> {
                val dest = state.trip.destinationStop ?: return@transition null
                RideState.ApproachingExitStop(
                    trip = state.trip,
                    stopsRemaining = event.stopsRemaining,
                    nextStop = state.trip.nextStop,
                    destinationStop = dest,
                )
            }

            is RideEvent.FusionScoreUpdated -> {
                // If confidence drops significantly, flag it but don't auto-exit
                // Require either location departure or user confirmation
                if (event.result.onBusConfidence < 0.3f) {
                    Timber.w("FSM: ON_BUS confidence dropped to ${event.result.onBusConfidence} — monitoring")
                }
                null
            }

            is RideEvent.UserConfirmedExit,
            is RideEvent.ArrivedAtDestination -> buildTripComplete(state)

            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── APPROACHING_EXIT_STOP ─────────────────────────────────────────────
        is RideState.ApproachingExitStop -> when (event) {
            is RideEvent.StopSequenceAdvanced -> state.copy(
                stopsRemaining = state.stopsRemaining - 1,
                nextStop = event.newNextStop,
            )

            is RideEvent.ApproachingDestination -> {
                if (event.stopsRemaining <= 1) {
                    RideState.ExitWindow(
                        trip = state.trip,
                        destinationStop = state.destinationStop,
                        secsToArrival = null,
                        escalationLevel = EscalationLevel.STRONG,
                    )
                } else state.copy(stopsRemaining = event.stopsRemaining)
            }

            is RideEvent.UserConfirmedExit,
            is RideEvent.ArrivedAtDestination -> buildTripComplete(state.trip)

            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── EXIT_WINDOW ───────────────────────────────────────────────────────
        is RideState.ExitWindow -> when (event) {
            is RideEvent.ArrivedAtDestination,
            is RideEvent.UserConfirmedExit -> buildTripComplete(state.trip)

            is RideEvent.EtaThresholdCrossed -> state.copy(
                secsToArrival = event.secsToArrival,
                escalationLevel = EscalationLevel.CRITICAL,
            )

            is RideEvent.ResetToIdle -> RideState.Idle
            else -> null
        }

        // ── TRIP_COMPLETE ─────────────────────────────────────────────────────
        is RideState.TripComplete -> when (event) {
            is RideEvent.ResetToIdle,
            is RideEvent.TripDismissed -> RideState.Idle
            else -> null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildTripComplete(onBusState: RideState.OnBus): RideState.TripComplete =
        RideState.TripComplete(
            boardedStop = null, // TODO: store boarded stop in OnBus state for next iteration
            exitedStop = onBusState.trip.destinationStop,
            routeName = onBusState.trip.route.routeShortName,
            durationMs = System.currentTimeMillis() - onBusState.boardedAt,
        )

    private fun buildTripComplete(trip: TripCandidate): RideState.TripComplete =
        RideState.TripComplete(
            boardedStop = null,
            exitedStop = trip.destinationStop,
            routeName = trip.route.routeShortName,
            durationMs = 0,
        )

    /** Force-reset for testing or user logout. */
    fun reset() {
        _state.value = RideState.Idle
    }
}
