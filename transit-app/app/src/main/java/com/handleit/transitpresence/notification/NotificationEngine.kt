package com.handleit.transitpresence.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.handleit.transitpresence.core.fsm.EscalationLevel
import com.handleit.transitpresence.core.fsm.RideState
import com.handleit.transitpresence.core.model.Stop
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Notification IDs
        const val ID_FOREGROUND_SERVICE = 1
        const val ID_BUS_APPROACHING = 2
        const val ID_BOARDING_WINDOW = 3
        const val ID_ON_BUS = 4
        const val ID_EXIT_WARNING = 5
        const val ID_PULL_CORD = 6
        const val ID_TRIP_COMPLETE = 7

        // Channels
        const val CHANNEL_FOREGROUND = "transit_foreground"
        const val CHANNEL_APPROACHING = "transit_approaching"
        const val CHANNEL_BOARDING = "transit_boarding"
        const val CHANNEL_ON_BUS = "transit_on_bus"
        const val CHANNEL_EXIT = "transit_exit"

        // Vibration patterns
        val VIBRATE_PASSIVE = longArrayOf(0, 200)
        val VIBRATE_ACTIVE = longArrayOf(0, 300, 200, 300)
        val VIBRATE_STRONG = longArrayOf(0, 500, 200, 500, 200, 500)
        val VIBRATE_CRITICAL = longArrayOf(0, 800, 100, 800, 100, 800, 100, 800)
    }

    init {
        createNotificationChannels()
    }

    // ── Channel creation ──────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        listOf(
            NotificationChannel(CHANNEL_FOREGROUND, "Transit Tracking", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Background location tracking service" },

            NotificationChannel(CHANNEL_APPROACHING, "Bus Approaching", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = "Bus is on its way to your stop"
                    vibrationPattern = VIBRATE_PASSIVE
                    enableVibration(true)
                },

            NotificationChannel(CHANNEL_BOARDING, "Boarding Alert", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Board the bus now"
                    vibrationPattern = VIBRATE_STRONG
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },

            NotificationChannel(CHANNEL_ON_BUS, "Riding", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Trip in progress" },

            NotificationChannel(CHANNEL_EXIT, "Exit Alert", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Prepare to exit the bus"
                    vibrationPattern = VIBRATE_STRONG
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
        ).forEach { nm.createNotificationChannel(it) }

        Timber.i("Notification: Channels created")
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    fun notify(state: RideState) {
        when (state) {
            is RideState.BusApproaching -> notifyBusApproaching(
                state.route.routeShortName, state.stop, state.secsToArrival
            )
            is RideState.BoardingWindow -> notifyBoardingWindow(
                state.route.routeShortName, state.stop, state.secsToArrival, state.escalationLevel
            )
            is RideState.OnBus -> notifyOnBus(
                state.trip.route.routeShortName, state.trip.nextStop
            )
            is RideState.ApproachingExitStop -> notifyApproachingExit(
                state.destinationStop, state.stopsRemaining
            )
            is RideState.ExitWindow -> notifyExitWindow(
                state.destinationStop, state.secsToArrival, state.escalationLevel
            )
            is RideState.TripComplete -> notifyTripComplete(state.routeName)
            is RideState.WaitingAtStop -> cancelBoardingAlerts()
            is RideState.Idle, is RideState.TripComplete -> cancelAll()
        }
    }

    // ── Foreground service notification ───────────────────────────────────────

    fun buildForegroundNotification(contentText: String = "Tracking transit..."): Notification =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setContentTitle("Transit Presence Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions) // Replace with custom icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    // ── State-specific notifications ──────────────────────────────────────────

    private fun notifyBusApproaching(routeName: String, stop: Stop, secsToArrival: Long) {
        val mins = secsToArrival / 60
        val secs = secsToArrival % 60
        val etaText = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

        post(
            id = ID_BUS_APPROACHING,
            channelId = CHANNEL_APPROACHING,
            title = "Bus $routeName approaching",
            text = "Arriving at ${stop.stopName} in $etaText",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            vibrate = VIBRATE_PASSIVE,
        )
    }

    private fun notifyBoardingWindow(
        routeName: String,
        stop: Stop,
        secsToArrival: Long,
        level: EscalationLevel,
    ) {
        cancelNotification(ID_BUS_APPROACHING)

        val title = when (level) {
            EscalationLevel.PASSIVE -> "Bus $routeName is almost here"
            EscalationLevel.ACTIVE -> "⚡ Bus $routeName — Get Ready!"
            EscalationLevel.STRONG -> "🚨 Board NOW — Bus $routeName"
            EscalationLevel.CRITICAL -> "🚨 BOARD BUS $routeName — ARRIVING"
        }

        val vibrate = when (level) {
            EscalationLevel.PASSIVE -> VIBRATE_PASSIVE
            EscalationLevel.ACTIVE -> VIBRATE_ACTIVE
            EscalationLevel.STRONG -> VIBRATE_STRONG
            EscalationLevel.CRITICAL -> VIBRATE_CRITICAL
        }

        post(
            id = ID_BOARDING_WINDOW,
            channelId = CHANNEL_BOARDING,
            title = title,
            text = "${stop.stopName} — ${secsToArrival}s",
            priority = NotificationCompat.PRIORITY_HIGH,
            vibrate = vibrate,
            fullScreenIntent = level >= EscalationLevel.STRONG,
        )

        vibrateDevice(vibrate)
    }

    private fun notifyOnBus(routeName: String, nextStop: Stop?) {
        cancelBoardingAlerts()
        post(
            id = ID_ON_BUS,
            channelId = CHANNEL_ON_BUS,
            title = "On Bus $routeName",
            text = nextStop?.let { "Next: ${it.stopName}" } ?: "Trip in progress",
            priority = NotificationCompat.PRIORITY_LOW,
            ongoing = true,
        )
    }

    private fun notifyApproachingExit(destination: Stop, stopsRemaining: Int) {
        val stopsText = if (stopsRemaining == 1) "1 stop" else "$stopsRemaining stops"
        post(
            id = ID_EXIT_WARNING,
            channelId = CHANNEL_EXIT,
            title = "Prepare to exit — $stopsText away",
            text = "Destination: ${destination.stopName}",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            vibrate = VIBRATE_ACTIVE,
        )
        vibrateDevice(VIBRATE_ACTIVE)
    }

    private fun notifyExitWindow(
        destination: Stop,
        secsToArrival: Long?,
        level: EscalationLevel,
    ) {
        cancelNotification(ID_EXIT_WARNING)

        val title = if (level >= EscalationLevel.STRONG) "🛑 Pull cord NOW" else "Exit at next stop"
        val text = secsToArrival?.let {
            "${destination.stopName} in ${it}s"
        } ?: "Exit at ${destination.stopName}"

        post(
            id = ID_PULL_CORD,
            channelId = CHANNEL_EXIT,
            title = title,
            text = text,
            priority = NotificationCompat.PRIORITY_HIGH,
            vibrate = VIBRATE_CRITICAL,
            fullScreenIntent = level >= EscalationLevel.STRONG,
        )
        vibrateDevice(VIBRATE_CRITICAL)
    }

    private fun notifyTripComplete(routeName: String) {
        cancelAll()
        post(
            id = ID_TRIP_COMPLETE,
            channelId = CHANNEL_ON_BUS,
            title = "Trip complete",
            text = "You've arrived. Route $routeName",
            priority = NotificationCompat.PRIORITY_LOW,
            autoCancel = true,
        )
    }

    // ── Cancel helpers ────────────────────────────────────────────────────────

    fun cancelBoardingAlerts() {
        cancelNotification(ID_BUS_APPROACHING)
        cancelNotification(ID_BOARDING_WINDOW)
    }

    fun cancelAll() {
        listOf(
            ID_BUS_APPROACHING, ID_BOARDING_WINDOW, ID_ON_BUS,
            ID_EXIT_WARNING, ID_PULL_CORD, ID_TRIP_COMPLETE
        ).forEach { cancelNotification(it) }
    }

    private fun cancelNotification(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    // ── Low-level post ────────────────────────────────────────────────────────

    private fun post(
        id: Int,
        channelId: String,
        title: String,
        text: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        vibrate: LongArray? = null,
        ongoing: Boolean = false,
        autoCancel: Boolean = false,
        fullScreenIntent: Boolean = false,
    ) {
        if (!hasNotificationPermission()) {
            Timber.w("Notification: Permission not granted")
            return
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setPriority(priority)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)

        if (vibrate != null) builder.setVibrate(vibrate)

        if (fullScreenIntent) {
            val fullScreenPendingIntent = buildMainActivityPendingIntent()
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun vibrateDevice(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun buildMainActivityPendingIntent(): PendingIntent {
        // Replace with actual MainActivity reference
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
