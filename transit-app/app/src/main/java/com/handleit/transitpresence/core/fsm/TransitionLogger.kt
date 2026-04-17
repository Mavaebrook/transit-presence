package com.handleit.transitpresence.core.fsm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a rolling in-memory log of all state transitions.
 * Exposed as StateFlow so the debug overlay screen can observe it live.
 * In a production build, this could flush to Room for session history.
 */
@Singleton
class TransitionLogger @Inject constructor() {

    private val _log = MutableStateFlow<List<StateTransitionLog>>(emptyList())
    val log: StateFlow<List<StateTransitionLog>> = _log.asStateFlow()

    fun log(entry: StateTransitionLog) {
        Timber.d(
            "TRANSITION [${entry.fromState} → ${entry.toState}] " +
            "event=${entry.triggerEvent} " +
            "confidence=${entry.confidence?.let { "%.2f".format(it) } ?: "n/a"}"
        )
        _log.value = (_log.value + entry).takeLast(100) // keep last 100 transitions
    }

    fun clear() { _log.value = emptyList() }
}
