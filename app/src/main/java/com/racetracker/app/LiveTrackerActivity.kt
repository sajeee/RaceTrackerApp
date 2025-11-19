package com.racetracker.app

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import android.media.MediaScannerConnection
import android.provider.Settings

class LiveTrackerActivity : AppCompatActivity(), OnMapReadyCallback {

    private val TAG = "LiveTrackerActivity"
    private val PREFS = "race_tracker_prefs"

    private var googleMap: GoogleMap? = null
    private var polyline: Polyline? = null
    private var runnerMarker: Marker? = null
    private val pathPoints = mutableListOf<LatLng>()
    private val elevations = mutableListOf<Double>()

    private lateinit var tvDistance: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvPace: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvTimer: TextView

    // Summary overlay views (from your updated XML)
    private var summaryCard: View? = null
    private var textSummaryDistance: TextView? = null
    private var textSummaryPace: TextView? = null
    private var textSummarySpeed: TextView? = null
    private var textSummaryElevation: TextView? = null
    private var textSummaryDuration: TextView? = null
    private var btnShareSummary: Button? = null

    private var raceId = 1
    private var runnerId = 1
    private var startTime: Long = 0L
    private var timerJob: Job? = null
    private var totalDistance = 0.0
    private var elevationGain = 0.0

    private var isTracking = false

    private val ACTION_TRACKING_UPDATE = "TRACKING_UPDATE"

    // Permission launcher for pre-Android 10 write storage (fallback)
    private val requestWritePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Storage permission required to save/share images on this OS version", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) ensureGpsEnabledOrPrompt()
        else Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
    }

    // Receives location updates from TrackingService
    private val trackingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val lat = intent.getDoubleExtra("lat", 0.0)
            val lon = intent.getDoubleExtra("lon", 0.0)
            val distanceStr = intent.getStringExtra("distance") ?: "0.00"
            val speedStr = intent.getStringExtra("speed") ?: "0.00"
            val paceStr = intent.getStringExtra("pace") ?: "0.00"
            val altitude = intent.getDoubleExtra("altitude", 0.0)

            runOnUiThread {
                // show distance on first line, IDs on second line so you can visually confirm/modify
                tvDistance.text = "🏁 $distanceStr km\nRace:${raceId} Runner:${runnerId}"
                tvSpeed.text = "⚡ $speedStr km/h"
                tvPace.text = "⏱ $paceStr min/km"

                if (lat != 0.0 && lon != 0.0) {
                    val newPoint = LatLng(lat, lon)
                    pathPoints.add(newPoint)
                    if (altitude != 0.0) {
                        elevations.add(altitude)
                        computeElevationGain()
                    }
                    totalDistance = distanceStr.toDoubleOrNull() ?: totalDistance
                    updatePolyline()
                    updateMarker(newPoint)
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(newPoint, 17f))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tracker)

        // Restore state if coming back from background
        savedInstanceState?.let {
            isTracking = it.getBoolean("isTracking", false)
            startTime = it.getLong("startTime", 0L)
            totalDistance = it.getDouble("totalDistance", 0.0)
            elevationGain = it.getDouble("elevationGain", 0.0)
        }

        // load saved IDs from prefs (keeps defaults=1)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        raceId = prefs.getInt("race_id", 1)
        runnerId = prefs.getInt("runner_id", 1)

        tvDistance = findViewById(R.id.textDistance)
        tvSpeed = findViewById(R.id.textSpeed)
        tvPace = findViewById(R.id.textPace)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // show initial IDs in distance text
        tvDistance.text = "🏁 0.00 km\nRace:${raceId} Runner:${runnerId}"

        // allow editing ids by tapping distance text
        tvDistance.setOnClickListener { showEditIdsDialog() }

        // summary overlay bindings (optional)
        summaryCard = findViewById(R.id.summaryCard)
        textSummaryDistance = findViewById(R.id.textSummaryDistance)
        textSummaryPace = findViewById(R.id.textSummaryPace)
        textSummarySpeed = findViewById(R.id.textSummarySpeed)
        textSummaryElevation = findViewById(R.id.textSummaryElevation)
        textSummaryDuration = findViewById(R.id.textSummaryDuration)
        btnShareSummary = findViewById(R.id.btnShareSummary)
        btnShareSummary?.setOnClickListener {
            takeActivityScreenshotAndSave { savedUri ->
                if (savedUri != null) shareUri(savedUri)
                else Toast.makeText(this, "Failed to create share image", Toast.LENGTH_SHORT).show()
            }
        }

        // Small floating timer (we still keep textTimer view in xml but ensure it's updated too if present)
        tvTimer = findViewByIdOrNull(R.id.textTimer) ?: TextView(this).apply {
            text = "⏱ 00:00:00"
            textSize = 16f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.parseColor("#E94E1B"))
            (findViewById<ViewGroup>(R.id.liveTrackerRoot)).addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val layoutParams = layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = 50
            this.layoutParams = layoutParams
        }

        // Map initialization
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapView) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Start/Stop buttons
        btnStart.setOnClickListener {
            if (hasLocationPermissions()) {
                if (!isGpsEnabled()) promptEnableGps() else startTrackingService()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }

        btnStop.setOnClickListener {
            stopTrackingService()
            stopTimer()
            showSummaryInOverlay()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(trackingReceiver, IntentFilter(ACTION_TRACKING_UPDATE))
    }

    override fun onStop() {
        try { unregisterReceiver(trackingReceiver) } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isTracking", isTracking)
        outState.putLong("startTime", startTime)
        outState.putDouble("totalDistance", totalDistance)
        outState.putDouble("elevationGain", elevationGain)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // When the user taps the notification
        // Activity is brought to front — restore UI safely
        if (intent.action == "OPEN_TRACKING_SCREEN") {
            // Optionally refresh UI / reload map
            Log.d(TAG, "Activity brought to front via notification")
        }
    }


    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = false
        try {
            googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.strava_map_style))
        } catch (e: Exception) {
            Log.w(TAG, "Map style load failed: ${e.message}")
        }
    }

    private fun updateMarker(point: LatLng) {
        val desc = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
        if (runnerMarker == null) {
            runnerMarker = googleMap?.addMarker(MarkerOptions().position(point).icon(desc).title("You"))
        } else {
            runnerMarker?.position = point
        }
    }

    private fun updatePolyline() {
        if (googleMap == null) return
        runOnUiThread {
            val color = ContextCompat.getColor(this, R.color.strava_orange)
            val opts = PolylineOptions().addAll(pathPoints).width(12f).color(color).jointType(JointType.ROUND)
            if (polyline == null) polyline = googleMap?.addPolyline(opts) else polyline?.points = pathPoints
        }
    }

    private fun computeElevationGain() {
        if (elevations.size < 2) return
        var gain = 0.0
        for (i in 1 until elevations.size) {
            val d = elevations[i] - elevations[i - 1]
            if (d > 0.5) gain += d
        }
        elevationGain = gain
    }

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java)
        // pass the current (possibly edited) IDs to the service
        intent.putExtra("runner_id", runnerId)
        intent.putExtra("race_id", raceId)
        ContextCompat.startForegroundService(this, intent)
        startTime = System.currentTimeMillis()
        startTimer()
        isTracking = true

        // persist IDs
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("runner_id", runnerId).putInt("race_id", raceId).apply()

        Toast.makeText(this, "🏃 Tracking started (race=$raceId runner=$runnerId)", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackingService() {
        stopService(Intent(this, TrackingService::class.java))
        isTracking = false
        
        // Save race data to analytics database
        saveRaceToAnalytics(raceId, runnerId, startTime, pathPoints, elevations, totalDistance)
        
        Toast.makeText(this, "⏹ Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                tvTimer.text = "⏱ " + formatTime(elapsed)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun showSummaryInOverlay() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        val avgSpeed = if (elapsed > 0) (totalDistance / (elapsed / 3600.0)) else 0.0
        val avgPace = if (totalDistance > 0) (elapsed / 60.0) / totalDistance else 0.0

        textSummaryDistance?.text = "Distance: %.2f km".format(totalDistance)
        textSummaryPace?.text = "Pace: %.2f min/km".format(avgPace)
        textSummarySpeed?.text = "Speed: %.2f km/h".format(avgSpeed)
        textSummaryElevation?.text = "Elevation Gain: %.0f m".format(elevationGain)
        textSummaryDuration?.text = "Duration: ${formatTime(elapsed)}"

        summaryCard?.visibility = View.VISIBLE
    }

    // snapshot + save (unchanged)
    private fun takeActivityScreenshotAndSave(callback: (Uri?) -> Unit) {
        googleMap?.snapshot { mapBitmap ->
            if (mapBitmap == null) { callback(null); return@snapshot }
            val overlayHeight = 360
            val combined = Bitmap.createBitmap(mapBitmap.width, mapBitmap.height + overlayHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            canvas.drawBitmap(mapBitmap, 0f, 0f, null)

            val paintBg = Paint().apply {
                shader = LinearGradient(0f, mapBitmap.height.toFloat(), 0f, (mapBitmap.height + overlayHeight).toFloat(),
                    Color.parseColor("#FF5722"), Color.parseColor("#FF8A50"), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, mapBitmap.height.toFloat(), mapBitmap.width.toFloat(), (mapBitmap.height + overlayHeight).toFloat(), paintBg)

            val paintText = Paint().apply {
                color = Color.WHITE
                textSize = 42f
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            val avgSpeed = if (elapsed > 0) (totalDistance / (elapsed / 3600.0)) else 0.0
            val avgPace = if (totalDistance > 0) (elapsed / 60.0) / totalDistance else 0.0

            var y = mapBitmap.height + 70f
            val lines = listOf(
                "🏁 Distance: %.2f km".format(totalDistance),
                "⏱ Duration: ${formatTime(elapsed)}",
                "⚡ Avg Speed: %.2f km/h".format(avgSpeed),
                "🕒 Avg Pace: %.2f min/km".format(avgPace),
                "🧗 Elevation: %.0f m".format(elevationGain)
            )
            for (line in lines) {
                canvas.drawText(line, 48f, y, paintText)
                y += 56f
            }

            CoroutineScope(Dispatchers.IO).launch {
                val savedUri = saveBitmapToPublicPictures(combined)
                withContext(Dispatchers.Main) { callback(savedUri) }
            }
        } ?: run { callback(null) }
    }

    private fun saveBitmapToPublicPictures(bitmap: Bitmap): Uri? {
        return try {
            val filename = "activity_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RaceTracker")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {

                    val outputStream = resolver.openOutputStream(uri)
                    val out: OutputStream = outputStream ?: return null

                    out.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    }

                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return uri
                }
                null
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return null
                }
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(picturesDir, "RaceTracker")
                if (!targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, filename)
                FileOutputStream(file).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap failed: ${e.message}")
            null
        }
    }

    private fun shareUri(uri: Uri) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "image/jpeg"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share activity"))
        } catch (e: Exception) {
            Log.e(TAG, "share failed: ${e.message}")
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && coarse
    }

    private fun ensureGpsEnabledOrPrompt() {
        if (!isGpsEnabled()) promptEnableGps()
        else try { googleMap?.isMyLocationEnabled = true } catch (_: SecurityException) {}
    }

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun promptEnableGps() {
        AlertDialog.Builder(this)
            .setTitle("Enable GPS")
            .setMessage("Please enable GPS for accurate tracking.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }.setNegativeButton("Cancel", null)
            .show()
    }

    // show dialog so user can change IDs on-the-fly
    private fun showEditIdsDialog() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 0)
        }
        val etRace = EditText(ctx).apply {
            hint = "Race ID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(raceId.toString())
        }
        val etRunner = EditText(ctx).apply {
            hint = "Runner ID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(runnerId.toString())
        }
        layout.addView(etRace)
        layout.addView(etRunner)

        AlertDialog.Builder(ctx)
            .setTitle("Edit Race / Runner IDs")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newRace = etRace.text.toString().toIntOrNull() ?: 1
                val newRunner = etRunner.text.toString().toIntOrNull() ?: 1
                val changed = (newRace != raceId) || (newRunner != runnerId)
                raceId = newRace
                runnerId = newRunner
                // persist
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("race_id", raceId).putInt("runner_id", runnerId).apply()
                // update visible text immediately
                val lines = tvDistance.text.toString().split("\n")
                val distLine = lines.getOrNull(0) ?: "🏁 0.00 km"
                tvDistance.text = "$distLine\nRace:${raceId} Runner:${runnerId}"

                if (changed && isTracking) {
                    // restart service with new ids (stop then start)
                    stopTrackingService()
                    // small delay to let service stop then start again
                    Handler(Looper.getMainLooper()).postDelayed({ startTrackingService() }, 400)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // helper: returns view by id or null
    private fun <T : View> findViewByIdOrNull(id: Int): T? {
        return try { findViewById(id) } catch (_: Exception) { null }
    }
}
