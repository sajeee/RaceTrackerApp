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
        
        // Location filtering parameters
        private const val MIN_ACCURACY_METERS = 50f
        private const val MAX_SPEED_MPS = 15.0 // ~54 km/h, reasonable max running speed
        private const val MIN_DISTANCE_METERS = 2f
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastValidLocation: Location? = null
    private var lastUpdateTime = 0L
    
    // Tracking state - FIXED: Changed to public for LiveTrackerActivity access
    var isTracking = false
        private set
    var isPaused = false
        private set
    var startTime: Long = 0
        private set
    private var pauseStartTime: Long = 0
    var totalPausedDuration: Long = 0
        private set
    
    // Location data
    private val locationPoints = mutableListOf<LatLng>()
    private val timestamps = mutableListOf<Long>()
    private val speeds = mutableListOf<Double>()
    private val elevations = mutableListOf<Double>()
    private var totalDistance = 0.0
    
    // Heart rate tracking - FIXED: Changed to public for LiveTrackerActivity access
    private var currentHeartRate = 0
    private val heartRateHistory = mutableListOf<Int>()
    private var maxHeartRate = 0
    
    // Cadence tracking - ADDED: New property for cadence
    private var currentCadence = 0
    
    // Split times
    private val splitTimes = mutableListOf<Long>()
    
    inner class LocalBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }
    
    private val binder = LocalBinder()
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Acquire WakeLock to prevent CPU from sleeping
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RaceTracker::TrackingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
        
        createNotificationChannel()
        setupLocationCallback()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Race Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows race tracking status"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    if (isLocationValid(location) && isTracking && !isPaused) {
                        processValidLocation(location)
                    }
                }
            }
        }
    }
    
    /**
     * Validates location quality based on accuracy, speed, and distance
     */
    private fun isLocationValid(location: Location): Boolean {
        // Check accuracy
        if (location.accuracy > MIN_ACCURACY_METERS) {
            return false
        }
        
        val lastLoc = lastValidLocation
        if (lastLoc != null) {
            // Check for unrealistic speed
            val distance = location.distanceTo(lastLoc)
            val timeDelta = (location.time - lastLoc.time) / 1000.0 // seconds
            
            if (timeDelta > 0) {
                val speed = distance / timeDelta
                if (speed > MAX_SPEED_MPS) {
                    return false
                }
            }
            
            // Check minimum distance moved
            if (distance < MIN_DISTANCE_METERS) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Process a validated location update
     */
    private fun processValidLocation(location: Location) {
        val currentTime = System.currentTimeMillis()
        
        // Throttle updates to max once per second
        if (currentTime - lastUpdateTime < 1000) {
            return
        }
        
        lastUpdateTime = currentTime
        
        // Store location data
        val latLng = LatLng(location.latitude, location.longitude)
        locationPoints.add(latLng)
        timestamps.add(currentTime)
        speeds.add((location.speed * 3.6).toDouble()) // Convert m/s to km/h
        
        // Calculate elevation if available
        if (location.hasAltitude()) {
            elevations.add(location.altitude)
        } else {
            elevations.add(0.0)
        }
        
        // Calculate distance
        lastValidLocation?.let { lastLoc ->
            val distance = location.distanceTo(lastLoc)
            if (distance > 0 && distance < 100) { // Filter unrealistic jumps
                totalDistance += distance
            }
        }
        
        lastValidLocation = location
        
        // Broadcast to LiveTrackerActivity
        val intent = Intent("LOCATION_UPDATE").apply {
            putExtra("latitude", location.latitude)
            putExtra("longitude", location.longitude)
            putExtra("speed", location.speed)
            putExtra("accuracy", location.accuracy)
            putExtra("timestamp", location.time)
        }
        sendBroadcast(intent)
        
        // Update notification with current speed
        updateNotification(location)
    }
    
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
    
    private fun startTracking() {
        if (!isTracking) {
            isTracking = true
            isPaused = false
            startTime = System.currentTimeMillis()
            totalPausedDuration = 0
            
            startForeground(NOTIFICATION_ID, buildNotification(null))
            startLocationUpdates()
        }
    }
    
    // FIXED: Changed to public for LiveTrackerActivity access
    fun stopTracking() {
        isTracking = false
        isPaused = false
        stopLocationUpdates()
        stopForeground(true)
        stopSelf()
    }
    
    fun pause() {
        if (isTracking && !isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
        }
    }
    
    fun resume() {
        if (isTracking && isPaused) {
            isPaused = false
            val pauseDuration = System.currentTimeMillis() - pauseStartTime
            totalPausedDuration += pauseDuration
        }
    }
    
    fun reset() {
        isTracking = false
        isPaused = false
        startTime = 0
        totalPausedDuration = 0
        pauseStartTime = 0
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
        lastValidLocation = null
    }
    
    // FIXED: Changed to public for LiveTrackerActivity access
    fun getTotalDistance(): Double = totalDistance
    
    // FIXED: Changed to public and renamed from getLocationPoints to match usage
    fun getLocationPoints(): List<LatLng> = locationPoints.toList()
    
    // FIXED: Changed to public for LiveTrackerActivity access
    fun getCurrentHeartRate(): Int = currentHeartRate
    
    // FIXED: Changed to public for LiveTrackerActivity access
    fun getMaxHeartRate(): Int = maxHeartRate
    
    // FIXED: Changed to public for LiveTrackerActivity access
    fun getAverageHeartRate(): Int {
        return if (heartRateHistory.isNotEmpty()) {
            heartRateHistory.average().toInt()
        } else {
            0
        }
    }
    
    // ADDED: New method to get current cadence
    fun getCurrentCadence(): Int = currentCadence
    
    // ADDED: New method to set current cadence
    fun setCurrentCadence(cadence: Int) {
        currentCadence = cadence
    }
    
    // ADDED: New method to get max speed
    fun getMaxSpeed(): Double {
        return if (speeds.isNotEmpty()) {
            speeds.maxOrNull() ?: 0.0
        } else {
            0.0
        }
    }
    
    fun getSplitTimes(): List<Long> = splitTimes.toList()
    
    fun setHeartRateCallback(callback: (Int) -> Unit) {
        // Placeholder for BLE heart rate integration
    }
    
    fun setCadenceCallback(callback: (Int) -> Unit) {
        // Placeholder for BLE cadence integration
    }
    
    fun update(heartRate: Int) {
        currentHeartRate = heartRate
        heartRateHistory.add(heartRate)
        if (heartRate > maxHeartRate) {
            maxHeartRate = heartRate
        }
    }
    
    fun startScanning() {
        // Placeholder for BLE scanning
    }
    
    fun stopScanning() {
        // Placeholder for BLE scanning
    }
    
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1 second interval
        ).apply {
            setMinUpdateIntervalMillis(500L) // Allow faster updates
            setMaxUpdateDelayMillis(2000L)
            setWaitForAccurateLocation(false) // Don't wait, get immediate updates
        }.build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    
    private fun buildNotification(location: Location?): Notification {
        val notificationIntent = Intent(this, LiveTrackerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val speedKmh = location?.let { 
            String.format("%.1f km/h", it.speed * 3.6)
        } ?: "Starting..."
        
        val status = when {
            isPaused -> "Paused"
            isTracking -> "Tracking - $speedKmh"
            else -> "Ready"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race Tracker")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(location: Location) {
        val notification = buildNotification(location)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
}
