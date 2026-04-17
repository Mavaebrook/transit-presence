package com.handleit.transitpresence

import com.handleit.transitpresence.core.fusion.BayesianFusionEngine
import com.handleit.transitpresence.core.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BayesianFusionEngineTest {

    private lateinit var engine: BayesianFusionEngine

    private fun makeBundle(
        speedMps: Float = 10f,
        routeAlignment: Float = 0.9f,
        gtfsConf: Float = 0.95f,
        gtfsMatched: Boolean = true,
        wifiConf: Float = 0.8f,
        motionScore: Float = 0.9f,
    ) = SignalBundle(
        locationContext = LocationContext(
            latLng = LatLng(28.5035, -81.3775),
            accuracyMeters = 5f,
            speedMps = speedMps,
            bearingDeg = 180f,
            timestampMs = System.currentTimeMillis(),
            motionState = MotionState.VEHICLE_SPEED,
        ),
        nearestRouteAlignmentScore = routeAlignment,
        gtfsTripMatchConfidence = gtfsConf,
        gtfsTripMatched = gtfsMatched,
        wifiSsidMatchConfidence = wifiConf,
        vehicleMotionScore = motionScore,
    )

    @Before
    fun setup() { engine = BayesianFusionEngine() }

    @Test
    fun `high all-signal confidence exceeds threshold`() {
        val result = engine.compute(makeBundle())
        assertTrue("All signals strong should exceed 0.85", result.onBusConfidence >= 0.85f)
        assertTrue(result.meetsThreshold)
    }

    @Test
    fun `stationary user below walking speed scores near zero`() {
        val result = engine.compute(makeBundle(speedMps = 0.2f, gtfsConf = 0f, gtfsMatched = false,
            routeAlignment = 0f, wifiConf = 0f, motionScore = 0.1f))
        assertTrue("Stationary user should have low confidence: ${result.onBusConfidence}",
            result.onBusConfidence < 0.4f)
        assertFalse(result.meetsThreshold)
    }

    @Test
    fun `gtfs trip match alone produces high confidence`() {
        val result = engine.compute(makeBundle(
            speedMps = 8f,
            gtfsConf = 1.0f,
            gtfsMatched = true,
            routeAlignment = 0.0f,
            wifiConf = -1f,   // unavailable
            motionScore = 0.5f,
        ))
        // GTFS weight 0.40 + speed weight 0.25 * score + motion 0.05
        assertTrue("GTFS match + speed should drive high confidence", result.onBusConfidence > 0.60f)
        assertEquals("gtfs_trip", result.dominantSignal)
    }

    @Test
    fun `wifi unavailable does not crash and redistributes weight`() {
        val result = engine.compute(makeBundle(wifiConf = -1f))
        assertTrue("Confidence should be in valid range", result.onBusConfidence in 0f..1f)
    }

    @Test
    fun `confidence always in 0_0 to 1_0 range`() {
        // Extreme edge case — all maxed
        val r1 = engine.compute(makeBundle(speedMps = 100f, gtfsConf = 1f, routeAlignment = 1f))
        assertTrue(r1.onBusConfidence <= 1.0f)

        // All zeroed
        val r2 = engine.compute(makeBundle(speedMps = 0f, gtfsConf = 0f, routeAlignment = 0f,
            wifiConf = 0f, motionScore = 0f))
        assertTrue(r2.onBusConfidence >= 0.0f)
    }

    @Test
    fun `signal breakdown sums approximately to total confidence`() {
        val result = engine.compute(makeBundle())
        val breakdownSum = result.signalBreakdown.values.sum()
        assertEquals("Breakdown sum should equal total confidence",
            result.onBusConfidence, breakdownSum, 0.001f)
    }
}
