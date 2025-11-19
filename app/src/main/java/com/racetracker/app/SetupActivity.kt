package com.hyperether.racetracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hyperether.racetracker.R
import com.hyperether.racetracker.analytics.AnalyticsActivity
import com.hyperether.racetracker.location.LocationHandler
import com.hyperether.racetracker.network.WeatherService
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private val locationHandler: LocationHandler by lazy { LocationHandler(this) }
    private lateinit var weatherTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var weatherIcon: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        weatherTextView = findViewById(R.id.weatherTextView)
        progressBar = findViewById(R.id.progressBar)
        weatherIcon = findViewById(R.id.weatherIcon)

        if (!hasLocationPermission()) {
            requestLocationPermission()
        } else {
            fetchWeather()
        }

        findViewById<View>(R.id.startButton).setOnClickListener {
            startActivity(Intent(this@SetupActivity, LiveTrackerActivity::class.java))
        }

        findViewById<View>(R.id.analyticsButton).setOnClickListener {
            startActivity(Intent(this@SetupActivity, AnalyticsActivity::class.java))
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
                weatherTextView.text = "Location permission denied"
            }
        }
    }

    private fun fetchWeather() {
        progressBar.visibility = View.VISIBLE
        weatherTextView.visibility = View.GONE
        weatherIcon.visibility = View.GONE

        locationHandler.getLocation { loc ->
            if (loc != null) {
                val lat = loc.latitude
                val lon = loc.longitude
                val apiKey = "ae1a03ab9625235fc8cb76418d77c0b5"

                // Use lifecycleScope to call the suspend function
                lifecycleScope.launch {
                    try {
                        val response = WeatherService.api.current(lat, lon, apiKey, "metric")
                        
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            weatherTextView.visibility = View.VISIBLE
                            weatherIcon.visibility = View.VISIBLE

                            val temp = response.main.temp
                            val description = response.weather.firstOrNull()?.description ?: "N/A"
                            weatherTextView.text = "${temp}°C, $description"

                            // Set weather icon based on description
                            when {
                                description.contains("clear", ignoreCase = true) ->
                                    weatherIcon.setImageResource(R.drawable.ic_sunny)
                                description.contains("cloud", ignoreCase = true) ->
                                    weatherIcon.setImageResource(R.drawable.ic_cloudy)
                                description.contains("rain", ignoreCase = true) ->
                                    weatherIcon.setImageResource(R.drawable.ic_rainy)
                                description.contains("snow", ignoreCase = true) ->
                                    weatherIcon.setImageResource(R.drawable.ic_snowy)
                                else ->
                                    weatherIcon.setImageResource(R.drawable.ic_sunny)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            weatherTextView.visibility = View.VISIBLE
                            weatherTextView.text = "Error: ${e.message}"
                            Toast.makeText(
                                this@SetupActivity,
                                "Weather fetch failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    weatherTextView.visibility = View.VISIBLE
                    weatherTextView.text = "Unable to get location"
                }
            }
        }
    }
}
