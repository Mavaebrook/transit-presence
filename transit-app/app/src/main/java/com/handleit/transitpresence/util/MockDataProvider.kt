package com.handleit.transitpresence.util

import com.handleit.transitpresence.core.model.*

/**
 * MockDataProvider
 *
 * Provides realistic Central Florida (LYNX) mock data for testing
 * without a live GTFS-RT feed. Toggle via debug settings.
 *
 * Sample stops/routes reflect actual LYNX network for plausibility.
 */
object MockDataProvider {

    val stops = listOf(
        Stop("1001", "S Orange Ave @ Michigan St", 28.5035, -81.3775),
        Stop("1002", "S Orange Ave @ Grant St", 28.5012, -81.3774),
        Stop("1003", "S Orange Ave @ Gore St", 28.4990, -81.3773),
        Stop("1004", "International Dr @ Sand Lake Rd", 28.4275, -81.4714),
        Stop("1005", "Downtown Orlando Transfer Hub", 28.5421, -81.3790),
        Stop("1006", "Orlando International Airport", 28.4312, -81.3081),
        Stop("1007", "UCF Main Campus", 28.6024, -81.2001),
        Stop("1008", "Florida Mall", 28.4720, -81.4665),
    )

    val routes = listOf(
        Route("111", "111", "S Orange Ave Limited", 3, "FF6600", "FFFFFF"),
        Route("8",   "8",   "East Colonial Dr", 3, "0066CC", "FFFFFF"),
        Route("18",  "18",  "International Dr", 3, "009900", "FFFFFF"),
        Route("51",  "51",  "OIA - Downtown Express", 3, "CC0000", "FFFFFF"),
        Route("407", "407", "University Blvd", 3, "6600CC", "FFFFFF"),
    )

    val trips = listOf(
        Trip("TRIP_111_AM", "111", "WD", "Downtown", 0, "SHAPE_111"),
        Trip("TRIP_8_AM",   "8",   "WD", "UCF",      0, "SHAPE_8"),
        Trip("TRIP_51_AM",  "51",  "WD", "OIA",      0, "SHAPE_51"),
    )

    fun mockArrivals(stopId: String, nowEpoch: Long = System.currentTimeMillis() / 1000): List<BusArrival> {
        val base = nowEpoch
        return listOf(
            BusArrival(
                routeId = "111", routeShortName = "111",
                tripId = "TRIP_111_AM", vehicleId = "BUS-4421",
                headsign = "Downtown Orlando",
                predictedArrivalEpoch = base + 240,
                scheduledArrivalEpoch = base + 300,
                isRealtime = true,
                secsToArrival = 240,
            ),
            BusArrival(
                routeId = "8", routeShortName = "8",
                tripId = "TRIP_8_AM", vehicleId = "BUS-3307",
                headsign = "UCF / Waterford Lakes",
                predictedArrivalEpoch = base + 720,
                scheduledArrivalEpoch = base + 720,
                isRealtime = false,
                secsToArrival = 720,
            ),
        )
    }

    fun mockVehiclePositions(): List<VehiclePosition> = listOf(
        VehiclePosition(
            vehicleId = "BUS-4421", tripId = "TRIP_111_AM", routeId = "111",
            lat = 28.5080f, lng = -81.3775f,
            bearing = 180f, speed = 8.5f,
            currentStopSequence = 3,
            currentStatus = VehicleStopStatus.IN_TRANSIT_TO,
            timestamp = System.currentTimeMillis() / 1000,
        ),
        VehiclePosition(
            vehicleId = "BUS-3307", tripId = "TRIP_8_AM", routeId = "8",
            lat = 28.5150f, lng = -81.3500f,
            bearing = 90f, speed = 12.0f,
            currentStopSequence = 7,
            currentStatus = VehicleStopStatus.IN_TRANSIT_TO,
            timestamp = System.currentTimeMillis() / 1000,
        ),
    )

    /**
     * Simulates a boarding sequence for integration testing.
     * Returns a sequence of (delayMs, LocationContext) pairs.
     */
    fun boardingSimulation(stopLat: Double = 28.5035, stopLng: Double = -81.3775): List<Pair<Long, LocationContext>> {
        val nowMs = System.currentTimeMillis()
        return listOf(
            // Standing at stop
            500L to LocationContext(LatLng(stopLat, stopLng), 5f, 0.1f, 180f, nowMs, MotionState.STATIONARY),
            // Bus arrives, start moving toward it
            2000L to LocationContext(LatLng(stopLat, stopLng), 4f, 0.8f, 180f, nowMs + 2000, MotionState.WALKING),
            // On bus — speed jumps
            3000L to LocationContext(LatLng(stopLat - 0.0003, stopLng), 5f, 4.5f, 180f, nowMs + 5000, MotionState.VEHICLE_SPEED),
            4000L to LocationContext(LatLng(stopLat - 0.0010, stopLng), 4f, 7.2f, 180f, nowMs + 9000, MotionState.VEHICLE_SPEED),
            5000L to LocationContext(LatLng(stopLat - 0.0020, stopLng), 4f, 9.8f, 180f, nowMs + 14000, MotionState.VEHICLE_SPEED),
        )
    }
}
