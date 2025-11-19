package com.racetracker.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class TrackingService : Service() {

    private val TAG = "TrackingService"
    private val CHANNEL_ID = "tracking_channel"
    private val ACTION_TRACKING_UPDATE = "TRACKING_UPDATE"
    private val PREFS = "race_tracker_prefs"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0.0
    private var startTime: Long = 0L
    private val pathPoints = mutableListOf<LatLng>()

    private var runnerId: Int = 1
    private var raceId: Int = 1
    
    // Wake lock to keep CPU running in background
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Prefer intent extras, otherwise fallback to SharedPreferences
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        runnerId = intent?.getIntExtra("runner_id", prefs.getInt("runner_id", 1)) ?: prefs.getInt("runner_id", 1)
        raceId = intent?.getIntExtra("race_id", prefs.getInt("race_id", 1)) ?: prefs.getInt("race_id", 1)

        startTime = System.currentTimeMillis()
        startForeground(1, buildNotification("Tracking started…"))
        startLocationUpdates()

        Log.i(TAG, "TrackingService started for runner $runnerId, race $raceId")
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RaceTracker::LocationWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing location permission")
            return
        }

        // Create high-accuracy request for FOREGROUND
        val foregroundRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.SECONDS.toMillis(2)  // Update every 2 seconds
        ).apply {
            setMinUpdateIntervalMillis(1000)  // At least 1 second apart
            setMinUpdateDistanceMeters(1f)     // At least 1 meter apart
            setMaxUpdateDelayMillis(TimeUnit.SECONDS.toMillis(4))
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            foregroundRequest,
            locationCallback,
            mainLooper
        )
        
        Log.i(TAG, "Location updates started with high accuracy")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                processLocation(location)
            }
        }
    }

    private fun processLocation(location: Location) {
        // Filter out inaccurate locations
        if (location.accuracy > 50) {
            Log.w(TAG, "Location accuracy too low: ${location.accuracy}m, skipping")
            return
        }
        
        if (lastLocation != null) {
            val dist = lastLocation!!.distanceTo(location)
            
            // Filter out unrealistic jumps (speed > 50 km/h for running)
            val timeDiff = (location.time - lastLocation!!.time) / 1000.0 // seconds
            if (timeDiff > 0) {
                val speed = (dist / timeDiff) * 3.6 // km/h
                if (speed > 50) {
                    Log.w(TAG, "Unrealistic speed: ${speed}km/h, skipping location")
                    return
                }
            }
            
            totalDistanceMeters += dist
        }
        lastLocation = location

        val speedKmh = location.speed * 3.6  // m/s -> km/h
        val distanceKm = totalDistanceMeters / 1000.0
        val pace = if (speedKmh > 0) 60.0 / speedKmh else 0.0

        val lat = location.latitude
        val lon = location.longitude
        val alt = location.altitude
        val newPoint = LatLng(lat, lon)
        pathPoints.add(newPoint)

        // Save route file (IO)
        CoroutineScope(Dispatchers.IO).launch {
            RouteStorage.saveLocations(applicationContext, pathPoints)
        }

        // Broadcast UI update (include altitude and accuracy)
        val intent = Intent(ACTION_TRACKING_UPDATE)
        intent.putExtra("lat", lat)
        intent.putExtra("lon", lon)
        intent.putExtra("distance", "%.2f".format(distanceKm))
        intent.putExtra("speed", "%.2f".format(speedKmh))
        intent.putExtra("pace", "%.2f".format(pace))
        intent.putExtra("altitude", alt)
        intent.putExtra("accuracy", location.accuracy)
        sendBroadcast(intent)

        // Post to server (IO)
        CoroutineScope(Dispatchers.IO).launch {
            postUpdateToServer(lat, lon, distanceKm, speedKmh, pace)
        }

        // Update notification
        updateNotification(
            "Distance: %.2f km".format(distanceKm), 
            "Speed: %.2f km/h | Acc: %.0fm".format(speedKmh, location.accuracy)
        )
        
        Log.d(TAG, "Location: ($lat, $lon), dist=%.2f km, speed=%.2f km/h, acc=%.1fm".format(
            distanceKm, speedKmh, location.accuracy
        ))
    }

    private fun postUpdateToServer(lat: Double, lon: Double, dist: Double, speed: Double, pace: Double) {
        try {
            val POST_URL = "https://web-production-58a8f.up.railway.app/tracking/api/tracking/$raceId/post_location/"
            val url = URL(POST_URL)
            val json = JSONObject().apply {
                put("runner_id", runnerId)
                put("race_id", raceId)
                put("lat", lat)
                put("lon", lon)
                put("display_distance_km", dist)
                put("speed_kmh", speed)
                put("pace_min_per_km", pace)
                put("timestamp", System.currentTimeMillis())
            }

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }

            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            val code = conn.responseCode
            Log.d(TAG, "Server update: HTTP $code")

        } catch (e: Exception) {
            Log.e(TAG, "Server update failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Race Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing race tracking notifications"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String = ""): Notification {
        val notificationIntent = Intent(this, LiveTrackerActivity::class.java).apply {
            action = "OPEN_TRACKING_SCREEN"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        releaseWakeLock()
        Log.i(TAG, "TrackingService stopped")
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
