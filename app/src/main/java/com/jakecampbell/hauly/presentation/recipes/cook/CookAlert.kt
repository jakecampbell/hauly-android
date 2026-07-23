package com.jakecampbell.hauly.presentation.recipes.cook

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Play the default notification tone once, when a cook timer completes (R8.20).
 * Best-effort: a silent phone just stays quiet.
 */
fun playTimerSound(context: Context) {
    runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
    }
}

/**
 * Start a continuous vibration and keep it running until [stopTimerVibration]
 * (R8.20): a finished countdown buzzes until the user resets it. The looping
 * waveform (repeat index 0) keeps going on its own, so this is called once when
 * the first timer finishes — not on a timer. Best-effort on devices without a
 * vibrator.
 */
fun startTimerVibration(context: Context) {
    runCatching {
        vibrator(context)?.let { vib ->
            // Buzz 600ms, pause 400ms, repeat from the start until cancelled.
            val pattern = longArrayOf(0, 600, 400)
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }
}

/** Stop the vibration started by [startTimerVibration] (all timers reset). */
fun stopTimerVibration(context: Context) {
    runCatching { vibrator(context)?.cancel() }
}

private fun vibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
