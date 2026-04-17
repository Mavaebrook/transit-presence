package com.handleit.transitpresence.core.model

import kotlinx.serialization.Serializable

// ─── Geographic ──────────────────────────────────────────────────────────────

data class LatLng(val lat: Double, val lng: Double)

data class RoutePolyline(val points: List<LatLng>)

// ─── GTFS Static ─────────────────────────────────────────────────────────────

@Serializable
data class Stop(
    val stopId: String,
    val stopName: String,
    val lat: Double,
    val lng: Double,
    val wheelchairBoarding: Int = 0,
)

@Serializable
data class Route(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val routeType: Int,          // 3 = bus
    val routeColor: String = "",
    val routeTextColor: String = "",
)

@Serializable
data class Trip(
    val tripId: String,
    val routeId: String,
    val serviceId: String,
    val tripHeadsign: String = "",
    val directionId: Int = 0,    // 0 = outbound, 1 = inbound
    val shapeId: String = "",
)

@Serializable
data class StopTime(
    val tripId: String,
    val stopId: String,
    val stopSequence: Int,
    val arrivalTime: String,     // HH:MM:SS (may exceed 24:00 for overnight)
    val departureTime: String,
)

@Serializable
data class Shape(
    val shapeId: String,
    val points: List<ShapePoint>,
)

@Serializable
data class ShapePoint(
    val lat: Double,
    val lng: Double,
    val sequence: Int,
    val distTraveled: Double = 0.0,
)

// ─── GTFS-RT Live ─────────────────────────────────────────────────────────────

data class VehiclePosition(
    val vehicleId: String,
    val tripId: String?,
    val routeId: String?,
    val lat: Float,
    val lng: Float,
    val bearing: Float?,
    val speed: Float?,           // meters/sec
    val currentStopSequence: Int?,
    val currentStatus: VehicleStopStatus,
    val timestamp: Long,
)

enum class VehicleStopStatus { INCOMING_AT, STOPPED_AT, IN_TRANSIT_TO, UNKNOWN }

data class TripUpdate(
    val tripId: String,
    val routeId: String?,
    val vehicleId: String?,
    val stopTimeUpdates: List<StopTimeUpdate>,
    val timestamp: Long,
)

data class StopTimeUpdate(
    val stopId: String?,
    val stopSequence: Int?,
    val arrivalDelay: Int?,       // seconds
    val arrivalTime: Long?,       // unix epoch
    val departureDelay: Int?,
    val departureTime: Long?,
)

// ─── Composite / App-domain ───────────────────────────────────────────────────

/**
 * A stop enriched with live arrival prediction for a specific trip.
 */
data class NearbyStopWithArrivals(
    val stop: Stop,
    val distanceMeters: Float,
    val arrivals: List<BusArrival>,
)

data class BusArrival(
    val routeId: String,
    val routeShortName: String,
    val tripId: String,
    val vehicleId: String?,
    val headsign: String,
    val predictedArrivalEpoch: Long?,   // null = schedule-only
    val scheduledArrivalEpoch: Long?,
    val isRealtime: Boolean,
    val secsToArrival: Long,            // computed, may be negative (departed)
)

/**
 * Snapshot of the user's current physical context.
 */
data class LocationContext(
    val latLng: LatLng,
    val accuracyMeters: Float,
    val speedMps: Float,          // meters per second
    val bearingDeg: Float,
    val timestampMs: Long,
    val motionState: MotionState,
)

enum class MotionState { STATIONARY, WALKING, VEHICLE_SPEED, UNKNOWN }

/**
 * A matched trip candidate — the app's best guess at which trip the user is on.
 */
data class TripCandidate(
    val trip: Trip,
    val route: Route,
    val vehiclePosition: VehiclePosition?,
    val routeAlignmentScore: Float,   // 0–1
    val gtfsTripMatchConfidence: Float, // 0–1
    val remainingStops: List<StopTime>,
    val nextStop: Stop?,
    val destinationStop: Stop?,
)

/**
 * Raw bundle of signals fed into the Sensor Fusion Engine.
 */
data class SignalBundle(
    val locationContext: LocationContext,
    val nearestRouteAlignmentScore: Float,
    val gtfsTripMatchConfidence: Float,
    val gtfsTripMatched: Boolean,
    val wifiSsidMatchConfidence: Float,  // 0 if Wi-Fi unavailable/disabled
    val vehicleMotionScore: Float,       // from accelerometer pattern
)

/**
 * Output of the Sensor Fusion Engine.
 */
data class FusionResult(
    val onBusConfidence: Float,          // 0–1
    val dominantSignal: String,          // which signal drove the score
    val signalBreakdown: Map<String, Float>,
    val meetsThreshold: Boolean,         // confidence > BuildConfig.ON_BUS_CONFIDENCE_THRESHOLD
)

// ─── User Preferences ─────────────────────────────────────────────────────────

data class UserPreferences(
    val geofenceRadiusMeters: Float = 50f,
    val gtfsRtPollIntervalMs: Long = 10_000L,
    val onBusConfidenceThreshold: Float = 0.85f,
    val wifiDetectionEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val mockModeEnabled: Boolean = false,
    val destinationStopId: String? = null,
    val selectedRouteId: String? = null,
)
