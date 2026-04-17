package com.handleit.transitpresence.core.fusion

import com.handleit.transitpresence.core.model.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * BayesianFusionEngine
 *
 * Combines multiple sensor and data signals into a single ON_BUS confidence
 * score in [0.0, 1.0].
 *
 * Weight table (sums to 1.0 when all signals available):
 *   GTFS trip match (direct vehicle ID match)   : 0.40
 *   Movement speed (exceeds walking threshold)  : 0.25
 *   Route shape alignment (polyline proximity)  : 0.20
 *   Wi-Fi SSID detection (transit network)      : 0.10
 *   Vehicle motion pattern (accel/gyro)         : 0.05
 *
 * If a signal is unavailable, its weight is redistributed proportionally
 * to remaining signals so the total always sums to 1.0.
 */
@Singleton
class BayesianFusionEngine @Inject constructor() {

    companion object {
        // Walking threshold: 5 km/h = 1.39 m/s
        private const val WALKING_SPEED_MPS = 1.39f
        // Bus speed lower bound: ~10 km/h = 2.78 m/s
        private const val MIN_VEHICLE_SPEED_MPS = 2.78f
        // Full weight at bus highway speed: 60 km/h = 16.67 m/s
        private const val MAX_SPEED_SCORE_MPS = 16.67f

        // Base weights
        private const val W_GTFS_TRIP = 0.40f
        private const val W_SPEED = 0.25f
        private const val W_ROUTE_ALIGNMENT = 0.20f
        private const val W_WIFI = 0.10f
        private const val W_MOTION = 0.05f
    }

    /**
     * Compute ON_BUS confidence from a [SignalBundle].
     * @param bundle  — snapshot of all available signals
     * @param threshold — minimum confidence to be considered ON_BUS
     */
    fun compute(bundle: SignalBundle, threshold: Float = 0.85f): FusionResult {
        val speed = bundle.locationContext.speedMps

        // ── Raw scores per signal ──────────────────────────────────────────
        val gtfsScore = bundle.gtfsTripMatchConfidence
        val speedScore = computeSpeedScore(speed)
        val routeScore = bundle.nearestRouteAlignmentScore.coerceIn(0f, 1f)
        val wifiScore = bundle.wifiSsidMatchConfidence.coerceIn(0f, 1f)
        val motionScore = bundle.vehicleMotionScore.coerceIn(0f, 1f)

        // ── Signal availability mask ───────────────────────────────────────
        val wifiAvailable = bundle.wifiSsidMatchConfidence >= 0f // negative = not scanned
        val gtfsAvailable = bundle.gtfsTripMatchConfidence >= 0f

        // ── Effective weights (renormalize if signals unavailable) ─────────
        val weights = mutableMapOf(
            "gtfs_trip" to if (gtfsAvailable) W_GTFS_TRIP else 0f,
            "speed" to W_SPEED,
            "route_alignment" to W_ROUTE_ALIGNMENT,
            "wifi_ssid" to if (wifiAvailable) W_WIFI else 0f,
            "vehicle_motion" to W_MOTION,
        )
        val totalWeight = weights.values.sum()
        if (totalWeight < 1f && totalWeight > 0f) {
            val scale = 1f / totalWeight
            weights.keys.forEach { weights[it] = weights[it]!! * scale }
        }

        // ── Weighted score ─────────────────────────────────────────────────
        val scores = mapOf(
            "gtfs_trip" to gtfsScore,
            "speed" to speedScore,
            "route_alignment" to routeScore,
            "wifi_ssid" to wifiScore,
            "vehicle_motion" to motionScore,
        )

        val onBusConfidence = weights.entries.sumOf { (key, w) ->
            (w * (scores[key] ?: 0f)).toDouble()
        }.toFloat().coerceIn(0f, 1f)

        // ── Identify dominant signal ───────────────────────────────────────
        val dominantSignal = weights.entries
            .maxByOrNull { it.value * (scores[it.key] ?: 0f) }
            ?.key ?: "unknown"

        val breakdown = scores.mapValues { (k, v) ->
            (weights[k] ?: 0f) * v
        }

        Timber.v(
            "Fusion: confidence=%.3f dominant=%s [gtfs=%.2f speed=%.2f route=%.2f wifi=%.2f motion=%.2f]"
                .format(onBusConfidence, dominantSignal, gtfsScore, speedScore, routeScore, wifiScore, motionScore)
        )

        return FusionResult(
            onBusConfidence = onBusConfidence,
            dominantSignal = dominantSignal,
            signalBreakdown = breakdown,
            meetsThreshold = onBusConfidence >= threshold,
        )
    }

    /**
     * Scores speed on a curve:
     *  - Below walking threshold → 0.0
     *  - Walking to min vehicle → linear ramp 0.0–0.5
     *  - Min vehicle to max vehicle → linear ramp 0.5–1.0
     */
    private fun computeSpeedScore(speedMps: Float): Float = when {
        speedMps < WALKING_SPEED_MPS -> 0f
        speedMps < MIN_VEHICLE_SPEED_MPS -> {
            val t = (speedMps - WALKING_SPEED_MPS) / (MIN_VEHICLE_SPEED_MPS - WALKING_SPEED_MPS)
            t * 0.5f
        }
        else -> {
            val t = ((speedMps - MIN_VEHICLE_SPEED_MPS) /
                    (MAX_SPEED_SCORE_MPS - MIN_VEHICLE_SPEED_MPS)).coerceIn(0f, 1f)
            0.5f + t * 0.5f
        }
    }
}

// ─── Route Alignment Engine ───────────────────────────────────────────────────

/**
 * Computes how well the user's current position aligns with a GTFS route shape.
 * Returns a score in [0, 1].
 */
object RouteAlignmentEngine {

    private const val MAX_SNAP_DISTANCE_M = 50.0  // meters

    /**
     * Finds the closest point on the route polyline to [userPos] and
     * returns a score: 1.0 at 0m, 0.0 at [MAX_SNAP_DISTANCE_M].
     */
    fun computeAlignment(userPos: LatLng, routePolyline: RoutePolyline): Float {
        if (routePolyline.points.isEmpty()) return 0f

        var minDistM = Double.MAX_VALUE
        for (i in 0 until routePolyline.points.size - 1) {
            val dist = pointToSegmentDistanceM(
                userPos,
                routePolyline.points[i],
                routePolyline.points[i + 1],
            )
            if (dist < minDistM) minDistM = dist
        }

        return (1.0 - (minDistM / MAX_SNAP_DISTANCE_M)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Haversine distance between two LatLng points in meters. */
    fun haversineDistanceM(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0 // Earth radius meters
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val sinDLat = Math.sin(dLat / 2)
        val sinDLng = Math.sin(dLng / 2)
        val h = sinDLat * sinDLat +
                Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) *
                sinDLng * sinDLng
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun pointToSegmentDistanceM(p: LatLng, a: LatLng, b: LatLng): Double {
        val ab = LatLng(b.lat - a.lat, b.lng - a.lng)
        val ap = LatLng(p.lat - a.lat, p.lng - a.lng)
        val abDot = ab.lat * ab.lat + ab.lng * ab.lng
        if (abDot == 0.0) return haversineDistanceM(p, a)
        val t = ((ap.lat * ab.lat + ap.lng * ab.lng) / abDot).coerceIn(0.0, 1.0)
        val closest = LatLng(a.lat + t * ab.lat, a.lng + t * ab.lng)
        return haversineDistanceM(p, closest)
    }
}

// ─── Motion Classifier ────────────────────────────────────────────────────────

/**
 * Classifies raw accelerometer readings into a vehicle motion score [0, 1].
 * Vehicle motion has characteristic low-frequency, sustained vibration.
 * Walking has high-frequency, irregular peaks.
 */
object MotionClassifier {

    private const val VEHICLE_VIBRATION_THRESHOLD = 0.8f   // m/s² RMS
    private const val WALKING_PEAK_THRESHOLD = 3.0f         // m/s² peak

    /**
     * @param accelSamples — list of magnitude samples over ~2-second window (m/s²)
     * @return score 0.0 (walking/stationary) to 1.0 (vehicle)
     */
    fun classify(accelSamples: List<Float>): Float {
        if (accelSamples.isEmpty()) return 0f

        val rms = Math.sqrt(accelSamples.sumOf { (it * it).toDouble() } /
                accelSamples.size).toFloat()
        val peak = accelSamples.maxOrNull() ?: 0f
        val variance = accelSamples.let { s ->
            val mean = s.average().toFloat()
            s.sumOf { abs(it - mean).toDouble() }.toFloat() / s.size
        }

        // Vehicle pattern: moderate RMS, low variance (smooth ride)
        // Walking pattern: high peak, high variance
        val vehicleScore = when {
            peak > WALKING_PEAK_THRESHOLD -> 0.1f // likely walking
            rms in VEHICLE_VIBRATION_THRESHOLD..4.0f && variance < 1.5f -> 0.9f
            rms < VEHICLE_VIBRATION_THRESHOLD -> 0.3f // borderline / bus stopped
            else -> 0.5f
        }

        return vehicleScore
    }
}
