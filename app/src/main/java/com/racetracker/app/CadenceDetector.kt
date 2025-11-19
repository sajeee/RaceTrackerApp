package com.racetracker.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

class CadenceDetector(
    context: Context,
    private val onCadence: (spm: Double) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val windowMillis = 5000L
    private val peaks = ArrayList<Long>()
    private var lastPeak = 0L
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }
    fun stop() {
        sensorManager.unregisterListener(this)
        peaks.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val mag = sqrt(ax*ax + ay*ay + az*az)
        val now = System.currentTimeMillis()
        val magNoGravity = abs(mag - 9.81f)
        val threshold = 1.1 // device-dependent; tune if necessary

        if (magNoGravity > threshold) {
            if (now - lastPeak > 250) { // avoid double counting
                peaks.add(now)
                lastPeak = now
            }
        }

        // purge old
        val cutoff = now - windowMillis
        while (peaks.isNotEmpty() && peaks[0] < cutoff) peaks.removeAt(0)

        if (peaks.size >= 2) {
            val durationSec = (peaks.last() - peaks.first()).toDouble() / 1000.0
            if (durationSec > 0) {
                val steps = peaks.size.toDouble()
                val spm = (steps / durationSec) * 60.0
                onCadence(spm)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
