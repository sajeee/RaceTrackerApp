package com.racetracker.app

import android.content.Context
import android.location.Location
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.FileOutputStream

class RouteManager(private val context: Context) {
    private val points = ArrayList<LatLng>()

    fun addPoint(location: Location) {
        points.add(LatLng(location.latitude, location.longitude))
    }

    fun drawOnMap(map: GoogleMap, color: Int = 0xFF5A1F.toInt(), width: Float = 6f) {
        if (points.isEmpty()) return
        val opts = PolylineOptions().addAll(points).width(width).color(color)
        map.addPolyline(opts)
    }

    fun clear() = points.clear()

    fun saveAsGpx(folder: File): File {
        if (!folder.exists()) folder.mkdirs()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = "route_${sdf.format(Date())}.gpx"
        val out = File(folder, name)
        val factory = XmlPullParserFactory.newInstance()
        val serializer = factory.newSerializer()
        val fos = FileOutputStream(out)
        serializer.setOutput(fos, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "gpx")
        serializer.attribute(null, "version", "1.1")
        serializer.startTag(null, "trk")
        serializer.startTag(null, "name")
        serializer.text(name)
        serializer.endTag(null, "name")
        serializer.startTag(null, "trkseg")
        for (p in points) {
            serializer.startTag(null, "trkpt")
            serializer.attribute(null, "lat", p.latitude.toString())
            serializer.attribute(null, "lon", p.longitude.toString())
            serializer.endTag(null, "trkpt")
        }
        serializer.endTag(null, "trkseg")
        serializer.endTag(null, "trk")
        serializer.endTag(null, "gpx")
        serializer.endDocument()
        fos.flush()
        fos.close()
        return out
    }

    // Loading GPX is possible (simplified): parse <trkpt> lat/lon and add to points.
    fun loadGpx(file: File) {
        // left as exercise — simple XML parse, add to points.
    }
}
