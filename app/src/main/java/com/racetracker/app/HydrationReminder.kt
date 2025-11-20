package com.racetracker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.TimeUnit

class HydrationReminder(
    private val context: Context,
    private val intervalMinutes: Int = 20
) {
    private var lastReminderTime: Long = 0
    private var startTime: Long = 0
    private var pausedTime: Long = 0
    private var totalPausedDuration: Long = 0
    private var isRunning = false
    private var isPaused = false

    companion object {
        private const val CHANNEL_ID = "hydration_reminders"
        private const val NOTIFICATION_ID = 2001
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Hydration Reminders"
            val descriptionText = "Reminds you to stay hydrated during your run"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun start() {
        startTime = System.currentTimeMillis()
        lastReminderTime = startTime
        totalPausedDuration = 0
        isRunning = true
        isPaused = false
    }

    fun pause() {
        if (!isRunning) return
        isPaused = true
        pausedTime = System.currentTimeMillis()
    }

    fun resume() {
        if (!isRunning || !isPaused) return
        val pauseDuration = System.currentTimeMillis() - pausedTime
        totalPausedDuration += pauseDuration
        isPaused = false
    }

    fun stop() {
        isRunning = false
        isPaused = false
    }

    // ADDED: reset() method to clear all state
    fun reset() {
        lastReminderTime = 0
        startTime = 0
        pausedTime = 0
        totalPausedDuration = 0
        isRunning = false
        isPaused = false
    }

    fun checkReminder(elapsedTime: Long) {
        if (!isRunning || isPaused) return

        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
        val timeSinceLastReminder = System.currentTimeMillis() - lastReminderTime

        if (timeSinceLastReminder >= intervalMs) {
            sendHydrationNotification()
            lastReminderTime = System.currentTimeMillis()
        }
    }

    private fun sendHydrationNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💧 Hydration Reminder")
            .setContentText("Time to drink water! Stay hydrated during your run.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Handle missing POST_NOTIFICATIONS permission
            }
        }
    }
}
