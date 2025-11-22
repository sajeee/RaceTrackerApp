package com.racetracker.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import java.text.SimpleDateFormat
import java.util.*

class LiveTrackerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    internal var trackingService: TrackingService? = null
    private var isServiceBound = false

    // UI Elements
    private lateinit var distanceText: TextView
    private lateinit var paceText: TextView
    private lateinit var timeText: TextView
    private lateinit var heartRateText: TextView
    private lateinit var cadenceText: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button

    // Tracking state (UI-side)
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

    // Map polyline building
    private val polylineOptions = PolylineOptions().width(8f).color(0xFF2196F3.toInt())

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
            val binder = service as? TrackingService.LocalBinder
            binder?.let {
                trackingService = it.getService()
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isServiceBound = false
        }
    }

    // Broadcast receiver for location updates (service uses sendBroadcast with action "LOCATION_UPDATE")
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("LiveTracker", "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                "LOCATION_UPDATE" -> {
                    val lat = intent.getDoubleExtra("latitude", Double.NaN)
                    val lng = intent.getDoubleExtra("longitude", Double.NaN)
                    val speed = intent.getFloatExtra("speed", 0f)
                    val accuracy = intent.getFloatExtra("accuracy", 0f)

                    if (!lat.isNaN() && !lng.isNaN()) {
                        val point = LatLng(lat, lng)
                        addPointToMap(point)
                    }

                    // Update UI from service (authoritative values)
                    updateUIFromService()

                    Log.d("LiveTracker", "Location update: $lat, $lng, speed: $speed, acc: $accuracy")
                }
                "HEART_RATE_UPDATE" -> {
                    val hr = intent.getIntExtra("heart_rate", 0)
                    updateHeartRateUI(hr)
                }
                "CADENCE_UPDATE" -> {
                    val cadence = intent.getIntExtra("cadence", 0)
                    updateCadenceUI(cadence.toDouble())
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tracker)

        // Initialize views
        initializeViews()

        // Initialize map safely
        val frag = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        if (frag != null) {
            frag.getMapAsync(this)
        } else {
            Log.w("LiveTracker", "Map fragment not found in layout")
        }

        // Initialize Performance Modules
        initializePerformanceModules()

        // Register broadcast receiver (global broadcast) - make sure we unregister in onDestroy
        val filter = IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("HEART_RATE_UPDATE")
            addAction("CADENCE_UPDATE")
        }
        registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)


        // Setup button listeners
        setupButtonListeners()

        // Check and request permissions
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's running (so Activity can read values immediately)
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind to avoid leaks (UI will stop updating but service continues)
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            trackingService = null
        }
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
    }

    private fun initializeViews() {
        distanceText = findViewById(R.id.distance_text)
        paceText = findViewById(R.id.pace_text)
        timeText = findViewById(R.id.time_text)
        heartRateText = findViewById(R.id.heart_rate_text)
        cadenceText = findViewById(R.id.cadence_text)
        startButton = findViewById(R.id.start_button)
        pauseButton = findViewById(R.id.pause_button)
        stopButton = findViewById(R.id.stop_button)

        // Initial button states
        pauseButton.isEnabled = false
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

        bleHeartRateManager = BLEHeartRateManager(this) { heartRate ->
            updateHeartRateUI(heartRate)
        }

        bleSensorManager = BLERunningSensorManager { speedMps, cadenceSpm, strideLengthMeters, contactTimeMs ->
            cadenceDetector.recordStep()
            updateCadenceUI(cadenceSpm)
        }

        cadenceDetector = CadenceDetector(this) { cadence ->
            updateCadenceUI(cadence)
        }
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

        pauseButton.setOnClickListener {
            pauseTracking()
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

        // Background location (if targeting background updates, optional)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                // only request background permission when necessary (separate flow)
                // permissionsNeeded.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
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
        // Start tracking service with correct action constant
        val serviceIntent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // Bind to service so we can read stats
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Update state
        isTracking = true
        isPaused = false
        startTime = System.currentTimeMillis()
        totalPausedDuration = 0

        updateButtonStates()

        // Reset modules
        paceAlertManager.reset()
        hydrationReminder.reset()
        autoLapManager.reset()
        cadenceDetector.stop()
        cadenceDetector.start()

        // Start UI updates
        uiUpdateHandler.post(uiUpdateRunnable)

        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun pauseTracking() {
        if (!isTracking || isPaused) return

        // Pause in service and UI
        trackingService?.pause()
        isPaused = true
        pausedTime = System.currentTimeMillis()

        updateButtonStates()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)

        Toast.makeText(this, "Tracking paused", Toast.LENGTH_SHORT).show()
    }

    private fun resumeTracking() {
        if (!isTracking || !isPaused) return

        trackingService?.resume()
        isPaused = false
        totalPausedDuration += System.currentTimeMillis() - pausedTime

        updateButtonStates()
        uiUpdateHandler.post(uiUpdateRunnable)

        Toast.makeText(this, "Tracking resumed", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        if (!isTracking) return

        // Calculate final stats BEFORE stopping service
        trackingService?.let { service ->
            val duration = System.currentTimeMillis() - startTime - totalPausedDuration
            val distanceKm = service.getTotalDistance() / 1000.0 // Convert meters to km
            val avgPace = if (distanceKm > 0) (duration / 60000.0) / distanceKm else 0.0
            val maxSpeed = service.getMaxSpeed()
            val avgHeartRate = service.getAverageHeartRate()
            val maxHeartRate = service.getMaxHeartRate()
            val routePoints = service.getLocationPoints()
            val avgSpeedKmh = if (duration > 0) distanceKm / (duration / 3600000.0) else 0.0
            val caloriesBurned = calculateCalories(distanceKm, duration / 60000.0, avgSpeedKmh)
            val elevationGain = calculateElevationGain(routePoints)

            saveRaceToAnalytics(
                raceStartTime = startTime,
                distance = distanceKm,
                duration = duration,
                avgPace = avgPace,
                maxSpeed = maxSpeed,
                avgHeartRate = avgHeartRate,
                maxHeartRate = maxHeartRate,
                caloriesBurned = caloriesBurned,
                elevationGain = elevationGain,
                polylinePoints = routePoints
            )
        }

        // Tell service to stop and unbind
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(stopIntent) // ensure service receives stop action
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Reset UI state
        isTracking = false
        isPaused = false
        startTime = 0
        pausedTime = 0
        totalPausedDuration = 0

        updateButtonStates()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)

        paceAlertManager.reset()
        autoLapManager.reset()
        cadenceDetector.stop()
        trainingLoad = TrainingLoad()

        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates() {
        when {
            !isTracking -> {
                startButton.isEnabled = true
                startButton.text = "Start"
                pauseButton.isEnabled = false
                stopButton.isEnabled = false
            }
            isPaused -> {
                startButton.isEnabled = true
                startButton.text = "Resume"
                pauseButton.isEnabled = false
                stopButton.isEnabled = true
            }
            else -> {
                startButton.isEnabled = false
                pauseButton.isEnabled = true
                stopButton.isEnabled = true
            }
        }
    }

    /**
     * Update UI fields from the authoritative service values.
     * This avoids double-counting and keeps UI consistent.
     */
    private fun updateUIFromService() {
        trackingService?.let { service ->
            val distanceMeters = service.getTotalDistance()
            val distanceKm = distanceMeters / 1000.0
            distanceText.text = String.format("%.2f km", distanceKm)

            val duration = if (startTime > 0) System.currentTimeMillis() - startTime - totalPausedDuration else 0L
            val hours = duration / 3600000
            val minutes = (duration % 3600000) / 60000
            val seconds = (duration % 60000) / 1000
            timeText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

            // Speed: prefer instantaneous from last recorded speed (service stores speeds in km/h)
            val maxSpeed = service.getMaxSpeed()
            val speedText = if (maxSpeed > 0.0) String.format("%.1f km/h", maxSpeed) else "0.0 km/h"
            // Pace calculation based on average
            val pace = if (distanceKm > 0) (duration / 60000.0) / distanceKm else 0.0
            val paceMin = pace.toInt()
            val paceSec = ((pace - paceMin) * 60).toInt()
            paceText.text = String.format("%d:%02d /km", paceMin, paceSec)

            // Heart rate and cadence
            val currentHR = service.getCurrentHeartRate()
            heartRateText.text = if (currentHR > 0) "$currentHR bpm" else "--"
            val currentCad = service.getCurrentCadence()
            cadenceText.text = if (currentCad > 0) "$currentCad spm" else "--"
        }
    }

    /**
     * Add point to map and extend polyline
     */
    private fun addPointToMap(point: LatLng) {
        polylineOptions.add(point)
        googleMap.addPolyline(polylineOptions)

        // Move / bound camera to include new point(s)
        try {
            val boundsBuilder = LatLngBounds.builder()
            polylineOptions.points.forEach { boundsBuilder.include(it) }
            val bounds = boundsBuilder.build()
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        } catch (e: Exception) {
            // fallback: just move camera to the new point
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
        }
    }

    private fun updateHeartRateUI(heartRate: Int) {
        heartRateText.text = "$heartRate bpm"
    }

    private fun updateCadenceUI(cadence: Double) {
        cadenceText.text = "${cadence.toInt()} spm"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: IllegalArgumentException) {
            // not registered
        }

        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)

        if (::cadenceDetector.isInitialized) {
            cadenceDetector.stop()
        }

        if (::bleHeartRateManager.isInitialized) {
            bleHeartRateManager.disconnect()
        }
        if (::bleSensorManager.isInitialized) {
            bleSensorManager.disconnect()
        }

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // Permission callback
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* Utility functions (reused from your extension file) */

    fun calculateElevationGain(polylinePoints: List<LatLng>): Double {
        return 0.0
    }

    fun calculateCalories(
        distanceKm: Double,
        durationMinutes: Double,
        avgSpeedKmh: Double,
        weightKg: Double = 70.0
    ): Int {
        val met = when {
            avgSpeedKmh < 6.0 -> 6.0
            avgSpeedKmh < 8.0 -> 6.0 + (avgSpeedKmh - 6.0) * 1.15
            avgSpeedKmh < 10.0 -> 8.3 + (avgSpeedKmh - 8.0) * 0.75
            avgSpeedKmh < 12.0 -> 9.8 + (avgSpeedKmh - 10.0) * 0.85
            avgSpeedKmh < 14.0 -> 11.5 + (avgSpeedKmh - 12.0) * 1.0
            avgSpeedKmh < 16.0 -> 13.5 + (avgSpeedKmh - 14.0) * 0.75
            else -> 15.0 + (avgSpeedKmh - 16.0) * 0.5
        }
        val calories = met * weightKg * (durationMinutes / 60.0)
        return calories.toInt()
    }
}
