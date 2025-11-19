package com.racetracker.app

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RouteStorage {

    private const val FILE_NAME = "route_points.json"

    fun saveLocations(context: Context, points: List<LatLng>) {
        try {
            val jsonArray = JSONArray()
            for (p in points) {
                val obj = JSONObject()
                obj.put("lat", p.latitude)
                obj.put("lon", p.longitude)
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLocations(context: Context): List<LatLng> {
        val list = mutableListOf<LatLng>()
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val jsonText = file.readText()
            val jsonArray = JSONArray(jsonText)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val lat = obj.getDouble("lat")
                val lon = obj.getDouble("lon")
                list.add(LatLng(lat, lon))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
