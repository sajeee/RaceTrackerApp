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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
    // FIXED: Changed from private to internal for extension access
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
            trackingService?.let { service ->
                if (service.isTracking) {
                    isTracking = true
                    isPaused = service.isPaused
                    startTime = service.startTime
                    totalPausedDuration = service.totalPausedDuration
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

    // Broadcast receiver for location updates
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "LOCATION_UPDATE" -> {
                    val lat = intent.getDoubleExtra("latitude", 0.0)
                    val lng = intent.getDoubleExtra("longitude", 0.0)
                    val speed = intent.getFloatExtra("speed", 0f)
                    updateLocationOnMap(LatLng(lat, lng))
                    updateDistanceUI()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tracker)

        // Initialize views
        initializeViews()

        // Initialize map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Initialize Performance Modules
        initializePerformanceModules()

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("HEART_RATE_UPDATE")
            addAction("CADENCE_UPDATE")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(locationReceiver, filter)

        // Setup button listeners
        setupButtonListeners()

        // Check and request permissions
        checkPermissions()
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
        
        // FIXED: Pass proper callback lambdas to BLE managers
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

        // REMOVED: setHeartRateCallback and setCadenceCallback methods don't exist
        // Callbacks are now passed directly to constructors above
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
        
        // FIXED: Reset managers that have reset() method
        paceAlertManager.reset()
        hydrationReminder.reset()
        autoLapManager.reset()
        // REMOVED: cadenceDetector.reset() - method doesn't exist, use stop() instead
        cadenceDetector.stop()
        cadenceDetector.start()
        // REMOVED: trainingLoad.reset() - method doesn't exist, create new instance if needed

        // Start UI updates
        uiUpdateHandler.post(uiUpdateRunnable)

        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    // FIXED: Method name to match TrackingService.pause()
    private fun pauseTracking() {
        if (!isTracking || isPaused) return

        trackingService?.pause()
        isPaused = true
        pausedTime = System.currentTimeMillis()
        
        updateButtonStates()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        
        Toast.makeText(this, "Tracking paused", Toast.LENGTH_SHORT).show()
    }

    // FIXED: Method name to match TrackingService.resume()
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
            val distance = service.getTotalDistance() / 1000.0 // Convert meters to km
            val avgPace = if (distance > 0) (duration / 60000.0) / distance else 0.0
            
            // Get max speed from service
            val maxSpeed = service.getMaxSpeed()
            
            // Get heart rate data
            val avgHeartRate = service.getAverageHeartRate()
            val maxHeartRate = service.getMaxHeartRate()
            
            // Get route points
            val routePoints = service.getLocationPoints()
            
            // Calculate calories
            val avgSpeedKmh = if (duration > 0) distance / (duration / 3600000.0) else 0.0
            val caloriesBurned = calculateCalories(distance, duration / 60000.0, avgSpeedKmh)
            
            // Calculate elevation gain
            val elevationGain = calculateElevationGain(routePoints)
            
            // Save to analytics
            saveRaceToAnalytics(
                raceStartTime = startTime,
                distance = distance,
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

        // Stop service
        trackingService?.stopTracking()
        val serviceIntent = Intent(this, TrackingService::class.java)
        stopService(serviceIntent)
        
        // Unbind service
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Reset state
        isTracking = false
        isPaused = false
        startTime = 0
        pausedTime = 0
        totalPausedDuration = 0
        totalDistance = 0.0
        lastValidLocation = null

        // Update UI
        updateButtonStates()
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        
        // FIXED: Reset managers that have reset() method
        paceAlertManager.reset()
        // hydrationReminder doesn't have reset()
        autoLapManager.reset()
        cadenceDetector.stop()
        // trainingLoad doesn't have reset() - create new instance if needed
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

    private fun updateUIFromService() {
        trackingService?.let { service ->
            // Update distance
            totalDistance = service.getTotalDistance() / 1000.0 // Convert to km
            distanceText.text = String.format("%.2f km", totalDistance)

            // Update time
            val duration = System.currentTimeMillis() - startTime - totalPausedDuration
            val hours = duration / 3600000
            val minutes = (duration % 3600000) / 60000
            val seconds = (duration % 60000) / 1000
            timeText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

            // Update pace
            val pace = if (totalDistance > 0) (duration / 60000.0) / totalDistance else 0.0
            val paceMin = pace.toInt()
            val paceSec = ((pace - paceMin) * 60).toInt()
            paceText.text = String.format("%d:%02d /km", paceMin, paceSec)

            // Update heart rate
            val currentHR = service.getCurrentHeartRate()
            if (currentHR > 0) {
                heartRateText.text = "$currentHR bpm"
            }

            // Update cadence
            val currentCad = service.getCurrentCadence()
            if (currentCad > 0) {
                cadenceText.text = "$currentCad spm"
            }
        }
    }

    private fun updateLocationOnMap(location: LatLng) {
        // Calculate distance from last location
        lastValidLocation?.let { last ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                last.latitude, last.longitude,
                location.latitude, location.longitude,
                results
            )
            totalDistance += results[0] / 1000.0 // Convert to km
        }
        lastValidLocation = location

        // Update map
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 16f))
    }

    private fun updateDistanceUI() {
        distanceText.text = String.format("%.2f km", totalDistance)
    }

    private fun updateHeartRateUI(heartRate: Int) {
        heartRateText.text = "$heartRate bpm"
    }

    private fun updateCadenceUI(cadence: Double) {
        cadenceText.text = "${cadence.toInt()} spm"
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
        
        // Remove UI updates
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        
        // Stop cadence detector
        if (::cadenceDetector.isInitialized) {
            cadenceDetector.stop()
        }
        
        // Disconnect BLE
        if (::bleHeartRateManager.isInitialized) {
            bleHeartRateManager.disconnect()
        }
        if (::bleSensorManager.isInitialized) {
            bleSensorManager.disconnect()
        }
        
        // Unbind service
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }

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
}
