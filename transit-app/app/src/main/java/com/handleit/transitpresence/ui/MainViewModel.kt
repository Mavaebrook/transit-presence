package com.handleit.transitpresence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handleit.transitpresence.core.fsm.RideState
import com.handleit.transitpresence.core.fsm.StateTransitionLog
import com.handleit.transitpresence.core.fsm.TransitionLogger
import com.handleit.transitpresence.core.model.*
import com.handleit.transitpresence.data.gtfs.RouteDao
import com.handleit.transitpresence.data.gtfsrt.FeedStatus
import com.handleit.transitpresence.data.gtfsrt.GtfsRtClient
import com.handleit.transitpresence.location.RideOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI State ─────────────────────────────────────────────────────────────────

data class MainUiState(
    val rideState: RideState = RideState.Idle,
    val nearbyStop: Stop? = null,
    val availableRoutes: List<Route> = emptyList(),
    val feedStatus: FeedStatus = FeedStatus.IDLE,
    val mockModeEnabled: Boolean = false,
    val transitionLog: List<StateTransitionLog> = emptyList(),
    val showDebugOverlay: Boolean = false,
    val permissionsGranted: Boolean = false,
    val selectedDestination: Stop? = null,
)

// ─── UI Intents (MVI) ─────────────────────────────────────────────────────────

sealed class MainIntent {
    data class SelectRoute(val route: Route, val stop: Stop, val destination: Stop?) : MainIntent()
    object ConfirmBoarding : MainIntent()
    object ConfirmExit : MainIntent()
    object DismissTrip : MainIntent()
    object ResetToIdle : MainIntent()
    object ToggleDebugOverlay : MainIntent()
    data class ToggleMockMode(val enabled: Boolean) : MainIntent()
    data class SetPermissionsGranted(val granted: Boolean) : MainIntent()
    data class SetDestination(val stop: Stop?) : MainIntent()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val orchestrator: RideOrchestrator,
    private val gtfsRtClient: GtfsRtClient,
    private val routeDao: RouteDao,
    private val transitionLogger: TransitionLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeRideState()
        observeNearbyStop()
        observeFeedStatus()
        observeTransitionLog()
        loadRoutes()
    }

    fun dispatch(intent: MainIntent) {
        when (intent) {
            is MainIntent.SelectRoute ->
                orchestrator.selectRoute(intent.route, intent.stop, intent.destination)
            is MainIntent.ConfirmBoarding -> orchestrator.confirmBoarding()
            is MainIntent.ConfirmExit -> orchestrator.confirmExit()
            is MainIntent.DismissTrip -> orchestrator.dismissTrip()
            is MainIntent.ResetToIdle -> orchestrator.resetToIdle()
            is MainIntent.ToggleDebugOverlay ->
                _uiState.update { it.copy(showDebugOverlay = !it.showDebugOverlay) }
            is MainIntent.ToggleMockMode ->
                _uiState.update { it.copy(mockModeEnabled = intent.enabled) }
            is MainIntent.SetPermissionsGranted ->
                _uiState.update { it.copy(permissionsGranted = intent.granted) }
            is MainIntent.SetDestination ->
                _uiState.update { it.copy(selectedDestination = intent.stop) }
        }
    }

    private fun observeRideState() {
        orchestrator.rideState
            .onEach { state -> _uiState.update { it.copy(rideState = state) } }
            .launchIn(viewModelScope)
    }

    private fun observeNearbyStop() {
        orchestrator.nearbyStop
            .onEach { stop -> _uiState.update { it.copy(nearbyStop = stop) } }
            .launchIn(viewModelScope)
    }

    private fun observeFeedStatus() {
        gtfsRtClient.feedStatus
            .onEach { status -> _uiState.update { it.copy(feedStatus = status) } }
            .launchIn(viewModelScope)
    }

    private fun observeTransitionLog() {
        transitionLogger.log
            .onEach { log -> _uiState.update { it.copy(transitionLog = log) } }
            .launchIn(viewModelScope)
    }

    private fun loadRoutes() {
        viewModelScope.launch {
            routeDao.observeAll().collect { entities ->
                _uiState.update { it.copy(availableRoutes = entities.map { e -> e.toModel() }) }
            }
        }
    }
}
