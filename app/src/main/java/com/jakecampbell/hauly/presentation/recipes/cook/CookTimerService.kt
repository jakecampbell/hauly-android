package com.jakecampbell.hauly.presentation.recipes.cook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.jakecampbell.hauly.MainActivity
import com.jakecampbell.hauly.R
import com.jakecampbell.hauly.presentation.theme.CookMagenta
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that mirrors the live cook timers into an **ongoing,
 * magenta-background notification** (R8.21). It exists so the phone's notification
 * list shows the recipe and every running/finished timer, refreshed each second,
 * even while the app is off-screen. The colorised background is only honoured for a
 * foreground-service notification, which is why this is a service rather than a bare
 * [NotificationManagerCompat.notify].
 *
 * It owns no timer state — [CookModeController] remains the single source of truth.
 * The service simply observes [CookModeController.sessions], re-renders on a 1 Hz
 * tick (timer values are clock-derived), and **self-stops** the moment no timer is
 * active, so its lifetime is driven entirely by the timers themselves.
 */
@AndroidEntryPoint
class CookTimerService : Service() {

    @Inject lateinit var controller: CookModeController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        // Must enter the foreground promptly after startForegroundService; seed the
        // notification from the current snapshot, then let the observer keep it live.
        startInForeground(buildNotification(this, controller.sessions.value, SystemClock.elapsedRealtime()))
        if (!observing) {
            observing = true
            scope.launch { observe() }
        }
        // If the process is killed the in-memory timers are gone anyway, so there is
        // nothing to restore — don't relaunch with an empty state.
        return START_NOT_STICKY
    }

    /** Re-render once a second (and on any state change) until no timer is active. */
    private suspend fun observe() {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(1000)
            }
        }
        combine(controller.sessions, ticks) { sessions, _ -> sessions }.collect { sessions ->
            val now = SystemClock.elapsedRealtime()
            if (sessions.values.none { it.hasActiveTimer(now) }) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collect
            }
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(this, sessions, now))
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cook_timers"
        private const val NOTIF_ID = 4820

        /** Start the service (idempotent) — call whenever a timer becomes active. */
        fun start(context: Context) {
            val intent = Intent(context, CookTimerService::class.java)
            context.startForegroundService(intent)
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cook timers",
                // LOW: a silent status line. The completion tone/vibration are fired
                // separately (R8.20) so the notification itself must not make noise
                // on every one-second refresh.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

/** True if this session has a timer worth showing: running, or finished-but-unreset. */
private fun CookSession.hasActiveTimer(now: Long): Boolean =
    activeTimerLines(now).isNotEmpty()

/** Human-readable "Label 12:34" lines for this session's running/finished timers. */
private fun CookSession.activeTimerLines(now: Long): List<String> {
    val lines = mutableListOf<String>()
    overall.lineOrNull(now, "Overall")?.let(lines::add)
    steps.toSortedMap().forEach { (index, timer) ->
        timer.lineOrNull(now, "Step ${index + 1}")?.let(lines::add)
    }
    return lines
}

private fun TimerState.lineOrNull(now: Long, label: String): String? = when {
    finished -> "$label ⏰ done"
    isRunning -> "$label ${formatTimer(displayMs(now))}"
    else -> null // paused/idle timers aren't "active"
}

/**
 * Ongoing, magenta-backed notification listing every cooking recipe and its active
 * timers. With a single recipe the title is its name and the timers fill the body;
 * with several, the title summarises and each recipe heads its own block.
 */
private fun buildNotification(context: Context, sessions: Map<String, CookSession>, now: Long): Notification {
    val active = sessions.values
        .mapNotNull { session ->
            val lines = session.activeTimerLines(now)
            if (lines.isEmpty()) null else session.recipeName to lines
        }

    val title: String
    val collapsed: String
    val expanded: String
    if (active.size == 1) {
        val (name, lines) = active.first()
        title = name
        collapsed = lines.joinToString(" · ")
        expanded = lines.joinToString("\n")
    } else {
        title = "Cooking · ${active.size} recipes"
        collapsed = active.joinToString(" · ") { it.first }
        expanded = active.joinToString("\n\n") { (name, lines) ->
            "$name\n${lines.joinToString("\n")}"
        }
    }

    val tap = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(context, "cook_timers")
        .setSmallIcon(R.drawable.ic_frying_pan)
        .setContentTitle(title)
        .setContentText(collapsed)
        .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        .setColor(CookMagenta.toArgb())
        .setColorized(true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setContentIntent(tap)
        .build()
}
