package com.racetracker.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LiveTrackerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private var trackingService: TrackingService? = null
    private var isServiceBound = false

    // UI Elements
    private lateinit var distanceText: TextView
    private lateinit var paceText: TextView
    private lateinit var timeText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // Tracking state
    private var isTracking = false
    private var isPaused = false
    private var startTime: Long = 0
    private var pausedTime: Long = 0
    private var totalPausedDuration: Long = 0

    // Performance Modules
    private lateinit var paceAlertManager: PaceAlertManager
    private lateinit var hydrationReminder: HydrationReminder
    private lateinit var autoLapManager: AutoLapManager
    private lateinit var bleHeartRateManager: BLEHeartRateManager
    private lateinit var bleSensorManager: BLERunningSensorManager
    private lateinit var cadenceDetector: CadenceDetector
    private lateinit var trainingLoad: TrainingLoad
    internal lateinit var analyticsManager: AnalyticsManager

    // Distance tracking
    private var totalDistance = 0.0
    private var lastValidLocation: LatLng? = null

    // UI Update
    private val uiUpdateHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isTracking && !isPaused) {
                updateUIFromService()
            }
            uiUpdateHandler.postDelayed(this, 1000) // Update every second
        }
    }

    // Service Connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingService.LocalBinder
            trackingService = binder.getService()
            isServiceBound = true
            
            // Restore state if service was already tracking
            trackingService?.let { svc ->
                if (svc.isTracking) {
                    isTracking = true
                    isPaused = svc.isPaused
                    startTime = svc.startTime
                    totalPausedDuration = svc.totalPausedDuration
                    updateButtonStates()
                    uiUpdateHandler.post(uiUpdateRunnable)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isServiceBound = false
        }
    }

    // Broadcast Receiver for location updates
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "LOCATION_UPDATE" -> {
                    val latitude = intent.getDoubleExtra("latitude", 0.0)
                    val longitude = intent.getDoubleExtra("longitude", 0.0)
                    val newLocation = LatLng(latitude, longitude)
                    
                    // Update polyline on map
                    updatePolyline(newLocation)
                    
                    // Calculate distance
                    lastValidLocation?.let { lastLoc ->
                        val distance = calculateDistance(lastLoc, newLocation)
                        if (distance > 0 && distance < 100) { // Filter unrealistic jumps (>100m)
                            totalDistance += distance
                        }
                    }
                    lastValidLocation = newLocation
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tracker)

        // Initialize UI
        initializeViews()

        // Initialize Map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapView) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize Performance Modules
        initializePerformanceModules()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("LOCATION_UPDATE")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(locationReceiver, filter)

        // Setup button listeners
        setupButtonListeners()

        // Check and request permissions
        checkPermissions()
    }

    private fun initializeViews() {
        distanceText = findViewById(R.id.textDistance)
        paceText = findViewById(R.id.textPace)
        timeText = findViewById(R.id.textTimer)
        startButton = findViewById(R.id.btnStart)
        stopButton = findViewById(R.id.btnStop)

        // Initial button states
        stopButton.isEnabled = false
    }

    private fun initializePerformanceModules() {
        val sharedPrefs = getSharedPreferences("race_tracker_prefs", Context.MODE_PRIVATE)
        val targetPace = sharedPrefs.getFloat("target_pace", 6.0f)
        val targetDistance = sharedPrefs.getFloat("target_distance", 5.0f)
        val hydrationInterval = sharedPrefs.getInt("hydration_interval", 20)

        paceAlertManager = PaceAlertManager(this, targetPace)
        hydrationReminder = HydrationReminder(this, hydrationInterval)
        autoLapManager = AutoLapManager(this, 1.0) // 1 km auto-lap
        bleHeartRateManager = BLEHeartRateManager(this)
        bleSensorManager = BLERunningSensorManager(this)
        cadenceDetector = CadenceDetector(this)
        trainingLoad = TrainingLoad()
        analyticsManager = AnalyticsManager(this)
    }

    private fun setupButtonListeners() {
        startButton.setOnClickListener {
            if (!isTracking) {
                startTracking()
            } else if (isPaused) {
                resumeTracking()
            }
        }

        stopButton.setOnClickListener {
            stopTracking()
        }
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Bluetooth permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 100)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Enable location layer if permission granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            googleMap.isMyLocationEnabled = true
        }

        // Configure map UI
        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
        }
    }

    private fun startTracking() {
        // Start tracking service
        val serviceIntent = Intent(this, TrackingService::class.java)
        serviceIntent.action = "START"
        ContextCompat.startForegroundService(this, serviceIntent)
        
        // Bind to service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Update state
        isTracking = true
        isPaused = false
        startTime = System.currentTimeMillis()
        totalDistance = 0.0
        lastValidLocation = null
        totalPausedDuration = 0

        // Update UI
        updateButtonStates()
        uiUpdateHandler.post(uiUpdateRunnable)

        // Start performance modules - only when tracking starts
        paceAlertManager.reset()
        hydrationReminder.start()
        autoLapManager.reset()
        cadenceDetector.reset()
        trainingLoad.reset()

        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun resumeTracking() {
        val pauseDuration = System.currentTimeMillis() - pausedTime
        totalPausedDuration += pauseDuration
        isPaused = false

        // Resume service
        trackingService?.resume()

        // Resume performance modules
        hydrationReminder.resume()

        updateButtonStates()
        uiUpdateHandler.post(uiUpdateRunnable)
        Toast.makeText(this, "Tracking resumed", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        // Calculate final metrics
        val endTime = System.currentTimeMillis()
        val totalTimeMs = endTime - startTime - totalPausedDuration
        val totalTimeSec = totalTimeMs / 1000
        val avgPace = if (totalDistance > 0) (totalTimeSec / 60.0) / totalDistance else 0.0
        val avgHeartRate = 0 // Placeholder

        // Stop service
        val serviceIntent = Intent(this, TrackingService::class.java)
        serviceIntent.action = "STOP"
        startService(serviceIntent)
        
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Stop performance modules
        hydrationReminder.stop()
        paceAlertManager.reset()

        // Save analytics data
        saveRaceToAnalytics(
            distance = totalDistance,
            duration = totalTimeMs,
            avgPace = avgPace,
            avgHeartRate = avgHeartRate
        )

        // Reset state
        isTracking = false
        isPaused = false
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)

        // Update UI
        updateButtonStates()
        Toast.makeText(this, "Tracking stopped - Race saved!", Toast.LENGTH_SHORT).show()

        // Return to setup activity
        finish()
    }

    private fun updateButtonStates() {
        when {
            !isTracking -> {
                startButton.isEnabled = true
                startButton.text = "START"
                stopButton.isEnabled = false
            }
            isPaused -> {
                startButton.isEnabled = true
                startButton.text = "RESUME"
                stopButton.isEnabled = true
            }
            else -> {
                startButton.isEnabled = false
                stopButton.isEnabled = true
            }
        }
    }

    private fun updateUIFromService() {
        trackingService?.let { service ->
            // Update distance (use service's distance)
            val distance = service.getTotalDistance() / 1000.0 // Convert to km
            totalDistance = distance
            distanceText.text = String.format("🏁 %.2f km", distance)

            // Update time
            val elapsedTime = if (isPaused) {
                pausedTime - startTime - totalPausedDuration
            } else {
                System.currentTimeMillis() - startTime - totalPausedDuration
            }
            timeText.text = formatTime(elapsedTime)

            // Update pace
            val pace = if (distance > 0) {
                val timeInMinutes = elapsedTime / 60000.0
                timeInMinutes / distance
            } else {
                0.0
            }
            paceText.text = String.format("⏱ %.2f min/km", pace)

            // Check pace alerts - only when tracking and not paused
            if (isTracking && !isPaused && distance > 0) {
                paceAlertManager.checkPace(pace, distance)
            }

            // Check hydration reminder
            hydrationReminder.checkReminder(elapsedTime)

            // Check auto-lap
            autoLapManager.checkDistance(distance)
        }
    }

    private fun updatePolyline(newLocation: LatLng) {
        // Add point to polyline
        val polylineOptions = PolylineOptions()
            .add(newLocation)
            .color(Color.BLUE)
            .width(10f)
        
        trackingService?.getLocationPoints()?.let { points ->
            if (points.isNotEmpty()) {
                polylineOptions.addAll(points)
                googleMap.clear()
                googleMap.addPolyline(polylineOptions)
                
                // Update camera to show entire route
                if (points.size > 1) {
                    val boundsBuilder = LatLngBounds.Builder()
                    points.forEach { boundsBuilder.include(it) }
                    val bounds = boundsBuilder.build()
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } else {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 17f))
                }
            }
        }
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(end.latitude - start.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(start.latitude)) * 
                Math.cos(Math.toRadians(end.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c / 1000.0 // Convert to kilometers
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        val hours = (milliseconds / (1000 * 60 * 60))
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun saveRaceToAnalytics(
        distance: Double,
        duration: Long,
        avgPace: Double,
        avgHeartRate: Int
    ) {
        val sharedPrefs = getSharedPreferences("race_tracker_prefs", Context.MODE_PRIVATE)
        val raceId = sharedPrefs.getInt("race_id", 1)
        val runnerId = sharedPrefs.getInt("runner_id", 1)
        val weight = sharedPrefs.getFloat("user_weight", 70f)
        
        val startTimeMs = startTime
        val endTimeMs = System.currentTimeMillis()
        val locationPoints = trackingService?.getLocationPoints() ?: emptyList()
        val splitTimes = trackingService?.getSplitTimes() ?: emptyList()
        
        val raceData = RaceData(
            raceId = raceId,
            runnerId = runnerId,
            startTime = startTimeMs,
            endTime = endTimeMs,
            duration = duration,
            totalDistanceMeters = distance * 1000, // Convert km to meters
            averageSpeedKmh = if (duration > 0) (distance / (duration / 3600000.0)) else 0.0,
            maxSpeedKmh = 0.0, // TODO: Calculate from speeds
            averagePaceMinPerKm = avgPace,
            bestPaceMinPerKm = 0.0, // TODO: Calculate from splits
            elevationGainMeters = 0.0, // TODO: Calculate from elevations
            elevationLossMeters = 0.0,
            maxElevationMeters = 0.0,
            minElevationMeters = 0.0,
            caloriesBurned = distance * weight,
            averageHeartRate = avgHeartRate,
            maxHeartRate = trackingService?.getMaxHeartRate() ?: 0,
            pathPoints = locationPoints,
            elevations = emptyList(), // TODO: Get from service
            timestamps = emptyList(), // TODO: Get from service
            speeds = emptyList(), // TODO: Get from service
            splitTimes = splitTimes,
            splitPaces = emptyList() // TODO: Calculate from splits
        )
        
        // Save using coroutine
        lifecycleScope.launch {
            analyticsManager.saveRace(raceData)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Cleanup
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
        
        if (isServiceBound) {
            unbindService(serviceConnection)
        }

        // Stop performance modules
        hydrationReminder.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Permissions required for full functionality",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
