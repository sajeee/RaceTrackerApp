package com.racetracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine && coarse) {
            proceed()
        } else {
            // Show explanation and ask to open settings
            AlertDialog.Builder(this)
                .setTitle("Location required")
                .setMessage("Location permissions are required for live tracking. Please enable them in Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    })
                    finish()
                }
                .setNegativeButton("Exit") { _, _ -> finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check permissions quickly
        val fineCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fineCheck == PackageManager.PERMISSION_GRANTED && coarseCheck == PackageManager.PERMISSION_GRANTED) {
            proceed()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun proceed() {
        // If runner/race already set in prefs jump to LiveTracker, otherwise Setup
        val prefs = getSharedPreferences("race_tracker_prefs", MODE_PRIVATE)
        val hasRunner = prefs.contains("runner_id")
        val target = if (hasRunner) LiveTrackerActivity::class.java else SetupActivity::class.java

        startActivity(Intent(this, target))
        finish()
    }
}
