package com.racetracker.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs

class TrackingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "tracking_channel"
        const val NOTIFICATION_ID = 1

        private const val MIN_ACCURACY_METERS = 50f
        private const val MAX_SPEED_MPS = 15.0
        private const val MIN_DISTANCE_METERS = 2f
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastValidLocation: Location? = null
    private var lastUpdateTime = 0L

    // State
    var isTracking = false
        private set
    var isPaused = false
        private set

    var startTime: Long = 0
        private set
    private var pauseStartTime = 0L
    var totalPausedDuration = 0L
        private set

    // Data buffers
    private val locationPoints = mutableListOf<LatLng>()
    private val timestamps = mutableListOf<Long>()
    private val speeds = mutableListOf<Double>()
    private val elevations = mutableListOf<Double>()

    private var totalDistance = 0.0

    private var currentHeartRate = 0
    private val heartRateHistory = mutableListOf<Int>()
    private var maxHeartRate = 0
    private var currentCadence = 0

    private val splitTimes = mutableListOf<Long>()

    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        // ✅ CRITICAL: Call startForeground() IMMEDIATELY
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildInitialNotification())

        // Now do the rest of initialization
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Acquire WakeLock to prevent CPU from sleeping
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RaceTracker::TrackingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }

        setupLocationCallback()
    }

    /** 🚀🚀 FIX #1 — Start Foreground Immediately When Service Starts */
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startTracking()
            }
            ACTION_STOP -> {
                stopTracking()
            }
        }
        return START_STICKY
    }

    // Add this new method for initial notification
    private fun buildInitialNotification(): Notification {
        val notificationIntent = Intent(this, LiveTrackerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race Tracker")
            .setContentText("Initializing...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Race Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (isLocationValid(loc) && isTracking && !isPaused) {
                        processValidLocation(loc)
                    }
                }
            }
        }
    }

    private fun isLocationValid(location: Location): Boolean {
        if (location.accuracy > MIN_ACCURACY_METERS) return false

        val last = lastValidLocation
        if (last != null) {
            val distance = location.distanceTo(last)
            val dt = (location.time - last.time) / 1000.0

            if (dt > 0) {
                val speed = distance / dt
                if (speed > MAX_SPEED_MPS) return false
            }

            if (distance < MIN_DISTANCE_METERS) return false
        }
        return true
    }

    private fun processValidLocation(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < 1000) return
        lastUpdateTime = now

        val point = LatLng(location.latitude, location.longitude)
        locationPoints.add(point)
        timestamps.add(now)
        speeds.add(location.speed * 3.6)

        elevations.add(if (location.hasAltitude()) location.altitude else 0.0)

        lastValidLocation?.let { last ->
            val d = location.distanceTo(last)
            if (d > 0 && d < 100) totalDistance += d
        }
        lastValidLocation = location

        // Broadcast update
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("latitude", location.latitude)
            putExtra("longitude", location.longitude)
            putExtra("speed", location.speed)
            putExtra("accuracy", location.accuracy)
            putExtra("timestamp", location.time)
        }
        sendBroadcast(intent)

        updateNotification(location)
    }

    /** 🚀 Do nothing heavy before startForeground (already called earlier) */
    private fun startTracking() {
        if (!isTracking) {
            isTracking = true
            isPaused = false
            startTime = System.currentTimeMillis()
            totalPausedDuration = 0

            // Update notification (no need to call startForeground again)
            updateNotification(null)
            startLocationUpdates()
        }
    }

    fun stopTracking() {
        isTracking = false
        isPaused = false
        stopLocationUpdates()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun pause() {
        if (isTracking && !isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
            updateNotification(lastValidLocation) // ✅ Show "Paused" status
        }
    }

    fun resume() {
        if (isTracking && isPaused) {
            isPaused = false
            totalPausedDuration += System.currentTimeMillis() - pauseStartTime
            updateNotification(lastValidLocation) // ✅ Show "Tracking" status
        }
    }

    fun reset() {
        isTracking = false
        isPaused = false
        startTime = 0
        pauseStartTime = 0
        totalPausedDuration = 0
        totalDistance = 0.0

        locationPoints.clear()
        timestamps.clear()
        speeds.clear()
        elevations.clear()
        heartRateHistory.clear()

        currentHeartRate = 0
        maxHeartRate = 0
        currentCadence = 0
        splitTimes.clear()
    }

    fun getTotalDistance() = totalDistance
    fun getLocationPoints() = locationPoints.toList()
    fun getCurrentHeartRate() = currentHeartRate
    fun getMaxHeartRate() = maxHeartRate
    fun getAverageHeartRate() =
        if (heartRateHistory.isNotEmpty()) heartRateHistory.average().toInt() else 0

    fun getCurrentCadence() = currentCadence
    fun setCurrentCadence(c: Int) { currentCadence = c }

    fun getMaxSpeed(): Double = speeds.maxOrNull() ?: 0.0
    fun getSplitTimes() = splitTimes.toList()

    fun update(heartRate: Int) {
        currentHeartRate = heartRate
        heartRateHistory.add(heartRate)
        if (heartRate > maxHeartRate) maxHeartRate = heartRate
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).apply {
            setMinUpdateIntervalMillis(500)
            setMaxUpdateDelayMillis(2000)
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            req,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun buildNotification(location: Location?): Notification {
        val intent = Intent(this, LiveTrackerActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speedText = location?.let {
            String.format("%.1f km/h", it.speed * 3.6)
        } ?: "Starting…"

        val text =
            if (isPaused) "Paused"
            else if (isTracking) "Tracking • $speedText"
            else "Ready"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(location: Location?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(location))
    }

    override fun onDestroy() {
        stopLocationUpdates()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
