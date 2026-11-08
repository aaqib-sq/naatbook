package com.aistudio.mynaatnotebook.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, deliberate haptics for the moments the owner confirmed
 * (2026-11-08): lock keypad, wrong PIN, unlock, favourite, save,
 * delete, long-press. Nothing else vibrates — every tap buzzing
 * would be noise, and the keyboard already vibrates on its own.
 *
 * A phone motor's frequency is fixed hardware; what we choose is
 * duration (ms), strength (amplitude 1-255) and pattern.
 */
object Haptics {
    private const val AMP_LIGHT = 80
    private const val AMP_MEDIUM = 150
    private const val AMP_STRONG = 255

    /** Lock keypad: every key press, a light 10ms tick. */
    fun keypadTick(context: Context) = oneShot(context, 10L, AMP_LIGHT)

    /** Favourite heart (row menu and reader menu): light 15ms tick. */
    fun favoriteTick(context: Context) = oneShot(context, 15L, AMP_LIGHT)

    /** Success: correct unlock, PIN set, save. One 30ms medium pulse. */
    fun confirm(context: Context) = oneShot(context, 30L, AMP_MEDIUM)

    /** Delete confirmed: one heavy 50ms pulse. */
    fun deleteConfirm(context: Context) = oneShot(context, 50L, AMP_STRONG)

    /** Long-press on an entry (menu opens): one 20ms medium pulse. */
    fun longPress(context: Context) = oneShot(context, 20L, AMP_MEDIUM)

    /** Wrong PIN: two strong 100ms bursts with a 100ms gap between. */
    fun wrongPin(context: Context) {
        waveform(
            context,
            longArrayOf(0L, 100L, 100L, 100L),
            intArrayOf(0, AMP_STRONG, 0, AMP_STRONG)
        )
    }

    private fun vibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    private fun oneShot(context: Context, ms: Long, amplitude: Int) {
        try {
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {
            // Haptics must never break the action they confirm.
        }
    }

    private fun waveform(context: Context, timings: LongArray, amplitudes: IntArray) {
        try {
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {
            // Haptics must never break the action they confirm.
        }
    }
}
