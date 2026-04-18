package com.handleit.transitpresence.notification

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import com.handleit.transitpresence.core.fsm.RideState
import com.handleit.transitpresence.location.RideOrchestrator
import com.handleit.transitpresence.core.model.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * TransitTrackingService
 *
 * Foreground service that keeps location + orchestrator alive when the app
 * is backgrounded. Also registers the accelerometer listener for motion
 * classification input to the fusion engine.
 */
@AndroidEntryPoint
class TransitTrackingService : Service(), SensorEventListener {

    @Inject lateinit var orchestrator: RideOrchestrator
    @Inject lateinit var notificationEngine: NotificationEngine

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    companion object {
        const val ACTION_START = "com.handleit.transitpresence.START_TRACKING"
        const val ACTION_STOP = "com.handleit.transitpresence.STOP_TRACKING"
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        val foregroundNotification = notificationEngine.buildForegroundNotification()
        startForeground(NotificationEngine.ID_FOREGROUND_SERVICE, foregroundNotification)

        // Register accelerometer
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Start orchestrator
        val prefs = UserPreferences() // Load from DataStore in production
        orchestrator.start(prefs)

        // Observe state changes and fire notifications
        orchestrator.rideState
            .onEach { state ->
                notificationEngine.notify(state)
                // Update foreground notification text
                val statusText = when (state) {
                    is RideState.WaitingAtStop -> "Waiting at ${state.stop.stopName}"
                    is RideState.BusApproaching -> "Bus approaching — ${state.secsToArrival}s"
                    is RideState.BoardingWindow -> "BOARD NOW"
                    is RideState.OnBus -> "On route ${state.trip.route.routeShortName}"
                    is RideState.ApproachingExitStop -> "${state.stopsRemaining} stops to exit"
                    is RideState.ExitWindow -> "Exit at next stop"
                    is RideState.TripComplete -> "Trip complete"
                    else -> "Tracking transit..."
                }
                startForeground(
                    NotificationEngine.ID_FOREGROUND_SERVICE,
                    notificationEngine.buildForegroundNotification(statusText)
                )
            }
            .launchIn(scope)

        Timber.i("TransitTrackingService: Started")
    }

    override fun onDestroy() {
        orchestrator.stop()
        sensorManager.unregisterListener(this)
        scope.cancel()
        Timber.i("TransitTrackingService: Destroyed")
        super.onDestroy()
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        try {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                orchestrator.updateAccelSample(mag)
            }
        } catch (e: Exception) {
            Timber.e(e, "Sensor: Exception in onSensorChanged")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null
}
