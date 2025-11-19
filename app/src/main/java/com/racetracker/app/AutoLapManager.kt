package com.racetracker.app

class AutoLapManager(private val lapDistanceMeters: Double = 1000.0) {
    private var accumulated = 0.0
    private var lapCount = 0
    var onLap: ((lapIndex: Int, lapMeters: Double, totalMeters: Double) -> Unit)? = null

    fun reset() { accumulated = 0.0; lapCount = 0 }

    fun addDistance(deltaMeters: Double) {
        if (deltaMeters <= 0) return
        accumulated += deltaMeters
        while (accumulated >= lapDistanceMeters) {
            lapCount += 1
            onLap?.invoke(lapCount, lapDistanceMeters, lapCount * lapDistanceMeters)
            accumulated -= lapDistanceMeters
        }
    }
}
