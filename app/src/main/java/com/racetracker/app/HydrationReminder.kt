package com.racetracker.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Lightweight hydration reminder using postDelayed or AlarmManager.
 * Callback invoked every intervalMillis. Use app notification for background.
 */
class HydrationReminder(private val context: Context, private val intervalMinutes: Int = 20, private val onReminder: () -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            onReminder()
            handler.postDelayed(this, intervalMinutes * 60 * 1000L)
        }
    }

    fun start() {
        handler.postDelayed(runnable, intervalMinutes * 60 * 1000L)
    }
    fun stop() {
        handler.removeCallbacks(runnable)
    }
}
