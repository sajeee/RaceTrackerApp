package com.racetracker.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.racetracker.app.databinding.ActivitySetupBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.max
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: SharedPreferences

    // LOCATION
    private lateinit var fused: FusedLocationProviderClient

    // PERFORMANCE MODULES (files you added under com.racetracker.app)
    private lateinit var cadenceDetector: CadenceDetector
    private lateinit var autoLap: AutoLapManager
    private lateinit var paceAlert: PaceAlertManager
    private lateinit var hydrationReminder: HydrationReminder
    private val trainingLoad = TrainingLoad()

    // Runtime state
    private var metricsExpanded = false
    private var lastLocation: Location? = null
    private var totalDistance = 0.0
    private var startTimeMs: Long = 0L
    private var avgPower = 0.0

    // Local small wrapper for weather UI result
    data class WeatherResultSimple(val temp: Double, val windSpeed: Double, val humidity: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("race_tracker_prefs", MODE_PRIVATE)
        fused = LocationServices.getFusedLocationProviderClient(this)

        // Initialize detectors/managers (use constructors you added)
        cadenceDetector = CadenceDetector(this) { spm ->
            runOnUiThread {
                binding.tvStamina.text = String.format("%.0f spm", spm)
            }
        }

        autoLap = AutoLapManager(1000.0)
        autoLap.onLap = { lapIndex, lapMeters, totalMeters ->
            runOnUiThread {
                Toast.makeText(this, "Auto-lap: $lapIndex (+${lapMeters.toInt()} m)", Toast.LENGTH_SHORT).show()
            }
        }

        // Pace alert manager uses constructor callbacks (your PaceAlertManager supports this)
        paceAlert = PaceAlertManager(
            onTooSlow = { runOnUiThread { Toast.makeText(this, "Pace: Too slow", Toast.LENGTH_SHORT).show() } },
            onTooFast = { runOnUiThread { Toast.makeText(this, "Pace: Too fast", Toast.LENGTH_SHORT).show() } },
            onHrZoneAlert = { zone, hr -> runOnUiThread { binding.tvStamina.text = "HR Zone: $zone ($hr bpm)" } }
        )
        // example thresholds (mins per km)
        paceAlert.paceLowerMinsPerKm = 7.0
        paceAlert.paceUpperMinsPerKm = 3.5

        // Hydration reminder (your implementation takes context, minutes, callback)
        hydrationReminder = HydrationReminder(this, intervalMinutes = 20) {
            runOnUiThread { Toast.makeText(this, "Hydration reminder — take a sip", Toast.LENGTH_SHORT).show() }
        }

        // UI wiring + permissions + weather
        setupExpandableMetricsPanel()
        setupUIInputs()
        requestLocationIfNeeded()
        fetchWeatherOnce()
    }

    // -----------------------------
    //      LOCATION PERMISSION
    // -----------------------------
    private fun requestLocationIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                99
            )
        } else {
            beginLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginLocationUpdates()
        } else {
            Toast.makeText(this,
                "Location permission is required for performance metrics",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -----------------------------
    //      START GPS FEED
    // -----------------------------
    private fun beginLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return

        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleNewLocation(loc)
        }
    }

    // -----------------------------
    //      LOCATION -> METRICS
    // -----------------------------
    private fun handleNewLocation(loc: Location) {
        if (lastLocation != null) {
            val d = lastLocation!!.distanceTo(loc).toDouble()
            totalDistance += d
            autoLap.addDistance(d)

            // elapsed time in seconds
            val elapsedSec = if (startTimeMs > 0L) (System.currentTimeMillis() - startTimeMs) / 1000.0 else 0.0
            val speed = if (elapsedSec > 0.0) totalDistance / elapsedSec else loc.speed.toDouble()
            val elevDelta = loc.altitude - lastLocation!!.altitude
            val grade = PerformanceMetrics.gradeFromElevation(elevDelta, d.coerceAtLeast(1.0))

            val power = PerformanceMetrics.estimatePowerW(speed, grade)
            avgPower = if (avgPower == 0.0) power else PerformanceMetrics.smooth(avgPower, power, 0.1)
            trainingLoad.addPowerSample(power)

            val vo2 = PerformanceMetrics.estimateVo2(speed, grade)

            // pace alerts
            paceAlert.checkPace(speed)

            // update UI
            runOnUiThread {
                binding.tvEstimatedVo2.text = String.format("VO₂ Est: %.1f ml/kg/min", vo2)
                binding.tvTrainingLoad.text = String.format("NP: %.0f W", trainingLoad.normalizedPower())
            }
        } else {
            // first fix
            startTimeMs = System.currentTimeMillis()
            totalDistance = 0.0
            avgPower = 0.0
            trainingLoad.addPowerSample(0.0)
        }

        lastLocation = loc
    }

    // -----------------------------
    //      WEATHER (OpenWeather Retrofit)
    // -----------------------------
    private fun fetchWeatherOnce() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            binding.tvStatus.text = "Weather: permission required"
            return
        }

        fused.lastLocation.addOnSuccessListener { location: Location? ->
            val lat = location?.latitude
            val lon = location?.longitude
            if (lat == null || lon == null) {
                binding.tvStatus.text = "Weather: location unavailable"
                return@addOnSuccessListener
            }

            val keyId = resources.getIdentifier("openweather_api_key", "string", packageName)
            val apiKey = if (keyId != 0) getString(keyId) else ""

            if (apiKey.isBlank()) {
                binding.tvStatus.text = "Weather: API key missing"
                return@addOnSuccessListener
            }

            lifecycleScope.launch {
                try {
                    val response = WeatherService.api.current(lat, lon, apiKey, "metric")
                    runOnUiThread {
                        binding.tvStatus.text = "Weather: ${response.main.temp}°C • Wind ${response.wind.speed} m/s • Hum ${response.main.humidity}%"
                    }
                } catch (e: Exception) {
                    Log.e("SetupActivity", "Weather fetch failed: ${e.message}")
                    runOnUiThread { binding.tvStatus.text = "Weather: error" }
                }
            }
        }
    }

    // -----------------------------
    //      EXPANDABLE PANEL
    // -----------------------------
    private fun setupExpandableMetricsPanel() {
        binding.layoutMetricsHeader.setOnClickListener {
            metricsExpanded = !metricsExpanded
            if (metricsExpanded) {
                binding.layoutMetricsContent.visibility = View.VISIBLE
                rotateArrow(binding.imgExpandArrow, 0f, 180f)
            } else {
                binding.layoutMetricsContent.visibility = View.GONE
                rotateArrow(binding.imgExpandArrow, 180f, 0f)
            }
        }
    }

    private fun rotateArrow(view: View, from: Float, to: Float) {
        val anim = RotateAnimation(
            from, to,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        anim.duration = 250
        anim.fillAfter = true
        view.startAnimation(anim)
    }

    // -----------------------------
    //      EXISTING UI BEHAVIOR
    // -----------------------------
    private fun setupUIInputs() {
        binding.etRunnerId.setText(prefs.getInt("runner_id", 1).toString())
        binding.etRaceId.setText(prefs.getInt("race_id", 1).toString())

        binding.etRaceId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnStart.performClick()
                true
            } else false
        }

        binding.btnStart.setOnClickListener {
            val runnerId = binding.etRunnerId.text.toString().toIntOrNull() ?: 1
            val raceId = binding.etRaceId.text.toString().toIntOrNull() ?: 1

            prefs.edit()
                .putInt("runner_id", runnerId)
                .putInt("race_id", raceId)
                .apply()

            // start cadence + hydration then go to LiveTrackerActivity
            cadenceDetector.start()
            hydrationReminder.start()

            val i = Intent(this, LiveTrackerActivity::class.java)
            i.putExtra("runner_id", runnerId)
            i.putExtra("race_id", raceId)
            startActivity(i)
            // DON'T call finish() - keep SetupActivity in back stack
            // This allows proper navigation when returning from background
        }
        
        // Add Analytics button - Find it or create programmatically if not in XML
        val analyticsBtn = findViewById<android.widget.Button>(R.id.btnAnalytics)
        analyticsBtn?.setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        try { cadenceDetector.stop() } catch (_: Exception) {}
        try { hydrationReminder.stop() } catch (_: Exception) {}
        fused.removeLocationUpdates(locationCallback)
    }

}
