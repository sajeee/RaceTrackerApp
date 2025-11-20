package com.racetracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private val locationHandler: LocationHandler by lazy { LocationHandler(this) }
    
    private lateinit var etRaceId: TextInputEditText
    private lateinit var etRunnerId: TextInputEditText
    private lateinit var btnStart: MaterialButton
    private lateinit var btnAnalytics: MaterialButton
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Initialize views
        etRaceId = findViewById(R.id.etRaceId)
        etRunnerId = findViewById(R.id.etRunnerId)
        btnStart = findViewById(R.id.btnStart)
        btnAnalytics = findViewById(R.id.btnAnalytics)
        tvStatus = findViewById(R.id.tvStatus)

        // Setup button listeners
        btnStart.setOnClickListener {
            val raceId = etRaceId.text.toString().toIntOrNull() ?: 1
            val runnerId = etRunnerId.text.toString().toIntOrNull() ?: 1
            
            // Save to SharedPreferences
            val sharedPrefs = getSharedPreferences("race_tracker_prefs", MODE_PRIVATE)
            sharedPrefs.edit().apply {
                putInt("race_id", raceId)
                putInt("runner_id", runnerId)
                apply()
            }
            
            // Start LiveTrackerActivity
            val intent = Intent(this, LiveTrackerActivity::class.java)
            startActivity(intent)
        }

        btnAnalytics.setOnClickListener {
            val intent = Intent(this, AnalyticsActivity::class.java)
            startActivity(intent)
        }

        // Check permissions
        if (!hasLocationPermission()) {
            requestLocationPermission()
        } else {
            fetchWeather()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchWeather()
            } else {
                tvStatus.text = "Location permission denied"
            }
        }
    }

    private fun fetchWeather() {
        locationHandler.getLastKnownLocation { location ->
            location?.let {
                lifecycleScope.launch {
                    try {
                        val response = WeatherService.api.current(
                            lat = it.latitude,
                            lon = it.longitude,
                            apiKey = "YOUR_OPENWEATHER_API_KEY", // Get free key from openweathermap.org
                            units = "metric"
                        )
                        val temp = response.main.temp
                        val condition = response.weather.firstOrNull()?.description ?: "Unknown"
                        tvStatus.text = "Weather: %.1f°C, %s".format(temp, condition)
                    } catch (e: Exception) {
                        tvStatus.text = "Weather data unavailable"
                    }
                }
            } ?: run {
                tvStatus.text = "Location unavailable"
            }
        }
    }
}
