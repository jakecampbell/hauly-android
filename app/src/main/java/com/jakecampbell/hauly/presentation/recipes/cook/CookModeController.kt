package com.jakecampbell.hauly.presentation.recipes.cook

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies a single timer inside a recipe's cook session: the always-present
 * overall timer, or a per-step timer keyed by the instruction line index.
 */
sealed interface TimerKey {
    data object Overall : TimerKey
    data class Step(val lineIndex: Int) : TimerKey
}

/**
 * One stopwatch/timer. Time is derived from a monotonic [SystemClock] baseline so
 * a running timer stays accurate even while its UI is disposed (e.g. the user
 * swiped away). [setMs] == 0 means stopwatch mode (counts up); a positive value
 * means countdown mode. The mode is fixed by [setMs] at the moment Start is
 * pressed (R8.18).
 */
data class TimerState(
    /** Configured countdown target in ms; 0 for a stopwatch. */
    val setMs: Long = 0L,
    /** Time counted while paused. */
    val accumulatedMs: Long = 0L,
    /** [SystemClock.elapsedRealtime] baseline when running; null while paused. */
    val runningSince: Long? = null,
    /** A countdown that reached zero (auto-paused at the set time). */
    val finished: Boolean = false,
) {
    val isRunning: Boolean get() = runningSince != null
    val isTimer: Boolean get() = setMs > 0L

    fun elapsedMs(now: Long): Long =
        accumulatedMs + (runningSince?.let { now - it } ?: 0L)

    /** Value to show: counts up for a stopwatch, down (clamped ≥0) for a timer. */
    fun displayMs(now: Long): Long {
        val elapsed = elapsedMs(now)
        return if (isTimer) (setMs - elapsed).coerceAtLeast(0L) else elapsed
    }
}

/** A recipe currently in cook mode, with its overall timer and any step timers. */
data class CookSession(
    val recipeId: String,
    val recipeName: String,
    val overall: TimerState = TimerState(),
    /** Step timers keyed by instruction line index; present == shown (R8.19). */
    val steps: Map<Int, TimerState> = emptyMap(),
)

/**
 * App-scoped, in-memory store for cook mode (R8.18). Cook sessions and their live
 * timers must outlive the per-recipe detail ViewModel (which is disposed shortly
 * after the user leaves the screen), so this is a [Singleton] holding the state as
 * a [StateFlow]. Timers are inherently transient runtime state, so nothing is
 * persisted to Room — sessions are lost on process death, which is acceptable for
 * a live stopwatch. No Notion/WorkManager involvement.
 */
@Singleton
class CookModeController @Inject constructor() {

    // Its own scope: the monitor loop that detects countdown completion must run
    // regardless of which screen (if any) is observing.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<Map<String, CookSession>>(emptyMap())
    val sessions: StateFlow<Map<String, CookSession>> = _sessions.asStateFlow()

    /** Emits each time a countdown reaches zero, so the app can sound the alert. */
    private val _completions = MutableSharedFlow<TimerKey>(extraBufferCapacity = 16)
    val completions: SharedFlow<TimerKey> = _completions.asSharedFlow()

    private var monitor: Job? = null

    /** Toggle cook mode for a recipe on/off. Turning it off drops all its timers. */
    fun toggle(recipeId: String, recipeName: String) {
        _sessions.update { current ->
            if (current.containsKey(recipeId)) current - recipeId
            else current + (recipeId to CookSession(recipeId, recipeName))
        }
        ensureMonitor()
    }

    /** Leave cook mode for a recipe. */
    fun stop(recipeId: String) {
        _sessions.update { it - recipeId }
    }

    /** Start (or resume) / pause a timer. Mode is fixed by its [setMs] at start. */
    fun startPause(recipeId: String, key: TimerKey) {
        val now = SystemClock.elapsedRealtime()
        updateTimer(recipeId, key) { t ->
            if (t.isRunning) {
                t.copy(accumulatedMs = t.elapsedMs(now), runningSince = null)
            } else {
                t.copy(runningSince = now, finished = false)
            }
        }
        ensureMonitor()
    }

    /**
     * Reset a timer. A stopwatch returns to 0. A timer returns to its set value;
     * resetting an already-idle timer a second time (or setting it to 0 by hand)
     * drops it back to stopwatch mode (R8.18).
     */
    fun reset(recipeId: String, key: TimerKey) {
        updateTimer(recipeId, key) { t ->
            when {
                // Idle timer already at full set value → second reset = stopwatch.
                t.isTimer && !t.isRunning && t.accumulatedMs == 0L && !t.finished ->
                    TimerState()
                t.isTimer -> TimerState(setMs = t.setMs)
                else -> TimerState()
            }
        }
    }

    /** Set a timer's duration (from the time picker). 0 → stopwatch mode. */
    fun setDuration(recipeId: String, key: TimerKey, durationMs: Long) {
        updateTimer(recipeId, key) {
            if (durationMs <= 0L) TimerState() else TimerState(setMs = durationMs)
        }
    }

    /** Long-press a step in cook mode: reveal its timer, or hide it if present. */
    fun toggleStepTimer(recipeId: String, lineIndex: Int) {
        _sessions.update { current ->
            val session = current[recipeId] ?: return@update current
            val steps = if (session.steps.containsKey(lineIndex)) {
                session.steps - lineIndex
            } else {
                session.steps + (lineIndex to TimerState())
            }
            current + (recipeId to session.copy(steps = steps))
        }
    }

    private fun updateTimer(recipeId: String, key: TimerKey, transform: (TimerState) -> TimerState) {
        _sessions.update { current ->
            val session = current[recipeId] ?: return@update current
            val updated = when (key) {
                TimerKey.Overall -> session.copy(overall = transform(session.overall))
                is TimerKey.Step -> {
                    val existing = session.steps[key.lineIndex] ?: TimerState()
                    session.copy(steps = session.steps + (key.lineIndex to transform(existing)))
                }
            }
            current + (recipeId to updated)
        }
    }

    /**
     * Runs while any session exists, ticking twice a second to catch countdowns
     * crossing zero — even when no timer UI is on screen. On completion it pins the
     * timer at its set value, auto-pauses it, marks it finished, and emits so the
     * app can vibrate + sound the alarm (R8.20).
     */
    private fun ensureMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                if (_sessions.value.isEmpty()) break
                val now = SystemClock.elapsedRealtime()
                val finished = mutableListOf<TimerKey>()
                _sessions.update { current ->
                    current.mapValues { (_, session) ->
                        val overall = session.overall.finishIfDue(now)?.also {
                            finished += TimerKey.Overall
                        } ?: session.overall
                        val steps = session.steps.mapValues { (index, timer) ->
                            timer.finishIfDue(now)?.also {
                                finished += TimerKey.Step(index)
                            } ?: timer
                        }
                        session.copy(overall = overall, steps = steps)
                    }
                }
                finished.forEach { _completions.tryEmit(it) }
                delay(500)
            }
            monitor = null
        }
    }

    /** Returns the finished state if this running countdown just reached zero. */
    private fun TimerState.finishIfDue(now: Long): TimerState? =
        if (isRunning && isTimer && !finished && elapsedMs(now) >= setMs) {
            copy(accumulatedMs = setMs, runningSince = null, finished = true)
        } else {
            null
        }
}
