package com.handleit.transitpresence.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handleit.transitpresence.core.fsm.EscalationLevel
import com.handleit.transitpresence.core.fsm.RideState
import com.handleit.transitpresence.core.model.*
import com.handleit.transitpresence.ui.MainIntent
import com.handleit.transitpresence.ui.MainUiState
import com.handleit.transitpresence.ui.theme.TransitColors
import kotlinx.coroutines.delay

// ─── Root screen router ───────────────────────────────────────────────────────

@Composable
fun RideScreen(
    uiState: MainUiState,
    onIntent: (MainIntent) -> Unit,
) {
    TransitBackground {
        AnimatedContent(
            targetState = uiState.rideState::class,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "RideScreenTransition",
        ) { _ ->
            when (val state = uiState.rideState) {
                is RideState.Idle -> IdleScreen(uiState, onIntent)
                is RideState.WaitingAtStop -> WaitingScreen(state, onIntent)
                is RideState.BusApproaching -> BusApproachingScreen(state, onIntent)
                is RideState.BoardingWindow -> BoardingWindowScreen(state, onIntent)
                is RideState.OnBus -> OnBusScreen(state, onIntent)
                is RideState.ApproachingExitStop -> ApproachingExitScreen(state, onIntent)
                is RideState.ExitWindow -> ExitWindowScreen(state, onIntent)
                is RideState.TripComplete -> TripCompleteScreen(state, onIntent)
            }
        }
    }
}

// ─── 1. IDLE / Home Screen ────────────────────────────────────────────────────

@Composable
fun IdleScreen(uiState: MainUiState, onIntent: (MainIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // Logo / Brand
        Text(
            "TRANSIT\nPRESENCE",
            style = MaterialTheme.typography.displayMedium,
            color = TransitColors.Accent,
            textAlign = TextAlign.Center,
            lineHeight = 38.sp,
        )
        Text(
            "Central Florida",
            style = MaterialTheme.typography.bodyMedium,
            color = TransitColors.TextSecondary,
        )

        Spacer(Modifier.height(48.dp))

        // Nearby stop card
        uiState.nearbyStop?.let { stop ->
            StatusCard(
                title = "NEARBY STOP DETECTED",
                color = TransitColors.Green,
            ) {
                Text(stop.stopName, style = MaterialTheme.typography.titleLarge, color = TransitColors.TextPrimary)
                Spacer(Modifier.height(12.dp))

                // Route selector
                if (uiState.availableRoutes.isNotEmpty()) {
                    Text(
                        "SELECT ROUTE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TransitColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    uiState.availableRoutes.take(6).forEach { route ->
                        RouteChip(route = route, onClick = {
                            onIntent(MainIntent.SelectRoute(route, stop, null))
                        })
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        } ?: run {
            // Scanning state
            ScanningIndicator("Scanning for nearby stops...")
        }

        Spacer(Modifier.weight(1f))

        FeedStatusBadge(feedStatus = uiState.feedStatus)
    }
}

// ─── 2. WAITING AT STOP Screen ────────────────────────────────────────────────

@Composable
fun WaitingScreen(state: RideState.WaitingAtStop, onIntent: (MainIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateHeader("WAITING AT STOP", TransitColors.Green)

        Spacer(Modifier.height(8.dp))

        Text(
            state.stop.stopName,
            style = MaterialTheme.typography.headlineLarge,
            color = TransitColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        RouteDirectionBadge(state.route)

        Spacer(Modifier.height(32.dp))

        if (state.arrivals.isEmpty()) {
            ScanningIndicator("Loading arrivals...")
        } else {
            Text(
                "NEXT BUS",
                style = MaterialTheme.typography.labelSmall,
                color = TransitColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            state.arrivals.take(3).forEach { arrival ->
                ArrivalCard(arrival)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = { onIntent(MainIntent.ResetToIdle) }) {
            Text("Cancel", color = TransitColors.TextSecondary)
        }
    }
}

// ─── 3. BUS APPROACHING Screen ────────────────────────────────────────────────

@Composable
fun BusApproachingScreen(state: RideState.BusApproaching, onIntent: (MainIntent) -> Unit) {
    val pulsate by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulsate",
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateHeader("BUS APPROACHING", TransitColors.Accent)

        Spacer(Modifier.height(32.dp))

        // Big ETA countdown
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (1f - state.secsToArrival / 300f).coerceIn(0f, 1f) },
                modifier = Modifier.size(160.dp),
                color = TransitColors.Accent,
                trackColor = TransitColors.Border,
                strokeWidth = 6.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountdownDisplay(secsToArrival = state.secsToArrival)
                Text("AWAY", style = MaterialTheme.typography.labelSmall, color = TransitColors.TextSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        StatusCard(
            title = "ROUTE ${state.route.routeShortName}",
            color = TransitColors.Accent,
        ) {
            Text(state.arrival.headsign.ifEmpty { state.route.routeLongName },
                style = MaterialTheme.typography.bodyMedium, color = TransitColors.TextPrimary)
            Text("AT: ${state.stop.stopName}",
                style = MaterialTheme.typography.bodyMedium, color = TransitColors.TextSecondary)
            state.arrival.vehicleId?.let {
                Text("Bus #$it", style = MaterialTheme.typography.labelSmall, color = TransitColors.Muted)
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "🚶 Position yourself at the stop",
            style = MaterialTheme.typography.bodyMedium,
            color = TransitColors.TextSecondary,
        )

        Spacer(Modifier.height(12.dp))
    }
}

// ─── 4. BOARDING WINDOW Screen ────────────────────────────────────────────────

@Composable
fun BoardingWindowScreen(state: RideState.BoardingWindow, onIntent: (MainIntent) -> Unit) {
    val bgColor = when (state.escalationLevel) {
        EscalationLevel.PASSIVE -> TransitColors.Yellow.copy(alpha = 0.05f)
        EscalationLevel.ACTIVE -> TransitColors.Orange.copy(alpha = 0.08f)
        EscalationLevel.STRONG, EscalationLevel.CRITICAL -> TransitColors.Red.copy(alpha = 0.12f)
    }

    val alertColor = when (state.escalationLevel) {
        EscalationLevel.PASSIVE -> TransitColors.Yellow
        EscalationLevel.ACTIVE -> TransitColors.Orange
        EscalationLevel.STRONG, EscalationLevel.CRITICAL -> TransitColors.Red
    }

    val flash by rememberInfiniteTransition(label = "flash").animateFloat(
        initialValue = if (state.escalationLevel >= EscalationLevel.STRONG) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "flashAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                when (state.escalationLevel) {
                    EscalationLevel.PASSIVE, EscalationLevel.ACTIVE -> "BOARD NOW"
                    else -> "🚨 BOARD NOW 🚨"
                },
                style = MaterialTheme.typography.displayMedium,
                color = alertColor,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            CountdownDisplay(secsToArrival = state.secsToArrival, large = true, color = alertColor)

            Spacer(Modifier.height(16.dp))

            Text(
                "ROUTE ${state.route.routeShortName}",
                style = MaterialTheme.typography.titleLarge,
                color = TransitColors.TextPrimary,
            )
            Text(
                state.stop.stopName,
                style = MaterialTheme.typography.bodyLarge,
                color = TransitColors.TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = { onIntent(MainIntent.ConfirmBoarding) },
                colors = ButtonDefaults.buttonColors(containerColor = alertColor),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "I'M ON THE BUS",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = { onIntent(MainIntent.ResetToIdle) }) {
                Text("Missed it", color = TransitColors.TextSecondary)
            }
        }
    }
}

// ─── 5. ON BUS Screen ────────────────────────────────────────────────────────

@Composable
fun OnBusScreen(state: RideState.OnBus, onIntent: (MainIntent) -> Unit) {
    val trip = state.trip
    val stopsTotal = trip.remainingStops.size + 1
    val stopsRemaining = trip.remainingStops.size
    val progress = if (stopsTotal > 0) 1f - (stopsRemaining.toFloat() / stopsTotal) else 0f

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        StateHeader("ON BUS", TransitColors.Green)

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "ROUTE ${trip.route.routeShortName}",
                style = MaterialTheme.typography.headlineMedium,
                color = TransitColors.TextPrimary,
            )
            ConfidenceBadge(confidence = state.fusionResult.onBusConfidence)
        }

        Text(
            trip.route.routeLongName,
            style = MaterialTheme.typography.bodyMedium,
            color = TransitColors.TextSecondary,
        )

        Spacer(Modifier.height(24.dp))

        // Progress bar
        Text("ROUTE PROGRESS", style = MaterialTheme.typography.labelSmall, color = TransitColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = TransitColors.Green,
            trackColor = TransitColors.Border,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Boarded", style = MaterialTheme.typography.labelSmall, color = TransitColors.Muted)
            if (stopsRemaining > 0)
                Text("$stopsRemaining stops left", style = MaterialTheme.typography.labelSmall, color = TransitColors.Muted)
        }

        Spacer(Modifier.height(24.dp))

        // Next stop card
        trip.nextStop?.let { next ->
            StatusCard(title = "NEXT STOP", color = TransitColors.Accent) {
                Text(next.stopName, style = MaterialTheme.typography.titleLarge, color = TransitColors.TextPrimary)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Destination card
        trip.destinationStop?.let { dest ->
            StatusCard(title = "YOUR DESTINATION", color = TransitColors.Green) {
                Text(dest.stopName, style = MaterialTheme.typography.titleMedium, color = TransitColors.TextPrimary)
                if (stopsRemaining > 0) {
                    Text("$stopsRemaining stops away",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TransitColors.TextSecondary)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onIntent(MainIntent.ConfirmExit) }) {
                Text("Exit now", color = TransitColors.TextSecondary)
            }
            TextButton(onClick = { onIntent(MainIntent.ResetToIdle) }) {
                Text("End trip", color = TransitColors.TextSecondary)
            }
        }
    }
}

// ─── 6. APPROACHING EXIT Screen ──────────────────────────────────────────────

@Composable
fun ApproachingExitScreen(state: RideState.ApproachingExitStop, onIntent: (MainIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateHeader("PREPARE TO EXIT", TransitColors.Orange)

        Spacer(Modifier.height(32.dp))

        Text("🔔", fontSize = 64.sp)

        Spacer(Modifier.height(16.dp))

        val stopsText = if (state.stopsRemaining == 1) "1 STOP AWAY" else "${state.stopsRemaining} STOPS AWAY"
        Text(stopsText, style = MaterialTheme.typography.headlineMedium, color = TransitColors.Orange)

        Spacer(Modifier.height(24.dp))

        StatusCard(title = "YOUR DESTINATION", color = TransitColors.Orange) {
            Text(state.destinationStop.stopName,
                style = MaterialTheme.typography.titleLarge,
                color = TransitColors.TextPrimary)
        }

        state.nextStop?.let { next ->
            Spacer(Modifier.height(12.dp))
            StatusCard(title = "NEXT STOP", color = TransitColors.Accent) {
                Text(next.stopName, style = MaterialTheme.typography.bodyLarge, color = TransitColors.TextPrimary)
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onIntent(MainIntent.ConfirmExit) },
            colors = ButtonDefaults.buttonColors(containerColor = TransitColors.Orange),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("I'VE EXITED", color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

// ─── 7. EXIT WINDOW Screen ───────────────────────────────────────────────────

@Composable
fun ExitWindowScreen(state: RideState.ExitWindow, onIntent: (MainIntent) -> Unit) {
    val flash by rememberInfiniteTransition(label = "exitFlash").animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "exitFlashAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TransitColors.Red.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text("🛑", fontSize = 80.sp)

            Spacer(Modifier.height(16.dp))

            Text(
                "PULL CORD NOW",
                style = MaterialTheme.typography.displayMedium,
                color = TransitColors.Red,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                state.destinationStop.stopName,
                style = MaterialTheme.typography.headlineMedium,
                color = TransitColors.TextPrimary,
                textAlign = TextAlign.Center,
            )

            state.secsToArrival?.let { secs ->
                Spacer(Modifier.height(8.dp))
                CountdownDisplay(secsToArrival = secs, color = TransitColors.Red)
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = { onIntent(MainIntent.ConfirmExit) },
                colors = ButtonDefaults.buttonColors(containerColor = TransitColors.Red),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("EXITED",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

// ─── 8. TRIP COMPLETE Screen ──────────────────────────────────────────────────

@Composable
fun TripCompleteScreen(state: RideState.TripComplete, onIntent: (MainIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✓", fontSize = 80.sp, color = TransitColors.Green)

        Spacer(Modifier.height(16.dp))

        Text("TRIP COMPLETE", style = MaterialTheme.typography.headlineLarge, color = TransitColors.Green)

        Spacer(Modifier.height(8.dp))

        Text("Route ${state.routeName}",
            style = MaterialTheme.typography.bodyLarge,
            color = TransitColors.TextSecondary)

        state.exitedStop?.let {
            Text(it.stopName, style = MaterialTheme.typography.bodyMedium, color = TransitColors.TextSecondary)
        }

        val durationMinutes = state.durationMs / 60_000
        Text("Duration: ${durationMinutes}m",
            style = MaterialTheme.typography.bodyMedium,
            color = TransitColors.Muted)

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = { onIntent(MainIntent.DismissTrip) },
            colors = ButtonDefaults.buttonColors(containerColor = TransitColors.Green),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("DONE", color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

// ─── Shared components ────────────────────────────────────────────────────────

@Composable
private fun TransitBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1530), TransitColors.Background)
                )
            )
    ) {
        content()
    }
}

@Composable
private fun StateHeader(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun StatusCard(title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TransitColors.Surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ArrivalCard(arrival: BusArrival) {
    val mins = arrival.secsToArrival / 60
    val secs = arrival.secsToArrival % 60
    val etaText = when {
        arrival.secsToArrival < 0 -> "Departed"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TransitColors.SurfaceVariant),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(arrival.routeShortName.ifEmpty { arrival.routeId },
                    style = MaterialTheme.typography.titleMedium,
                    color = TransitColors.Accent)
                Text(arrival.headsign.ifEmpty { "Route ${arrival.routeId}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TransitColors.TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(etaText,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (mins < 2) TransitColors.Orange else TransitColors.TextPrimary)
                if (arrival.isRealtime) {
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = TransitColors.Green)
                } else {
                    Text("SCHED", style = MaterialTheme.typography.labelSmall, color = TransitColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun RouteChip(route: Route, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, TransitColors.Accent.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TransitColors.Accent),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(route.routeShortName, style = MaterialTheme.typography.titleMedium, color = TransitColors.Accent)
            Text(route.routeLongName, style = MaterialTheme.typography.bodyMedium, color = TransitColors.TextSecondary,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TransitColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RouteDirectionBadge(route: Route) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Surface(
            color = TransitColors.Accent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                "ROUTE ${route.routeShortName}",
                style = MaterialTheme.typography.labelSmall,
                color = TransitColors.Accent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float) {
    val color = when {
        confidence >= 0.85f -> TransitColors.Green
        confidence >= 0.6f -> TransitColors.Yellow
        else -> TransitColors.Orange
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            "${(confidence * 100).toInt()}% CONF",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CountdownDisplay(
    secsToArrival: Long,
    large: Boolean = false,
    color: Color = TransitColors.Accent,
) {
    val mins = secsToArrival / 60
    val secs = secsToArrival % 60
    val text = if (mins > 0) "${mins}m ${"%02d".format(secs)}s" else "${secs}s"
    Text(
        text,
        style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineLarge,
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ScanningIndicator(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = TransitColors.Accent,
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TransitColors.TextSecondary)
    }
}

@Composable
private fun FeedStatusBadge(feedStatus: com.handleit.transitpresence.data.gtfsrt.FeedStatus) {
    val (color, label) = when (feedStatus) {
        com.handleit.transitpresence.data.gtfsrt.FeedStatus.LIVE -> TransitColors.Green to "LIVE"
        com.handleit.transitpresence.data.gtfsrt.FeedStatus.CONNECTING -> TransitColors.Yellow to "CONNECTING"
        com.handleit.transitpresence.data.gtfsrt.FeedStatus.ERROR -> TransitColors.Red to "FEED ERROR"
        com.handleit.transitpresence.data.gtfsrt.FeedStatus.DEGRADED -> TransitColors.Orange to "DEGRADED"
        else -> TransitColors.Muted to "OFFLINE"
    }
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
