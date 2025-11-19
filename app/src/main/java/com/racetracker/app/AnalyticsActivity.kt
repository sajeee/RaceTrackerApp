package com.racetracker.app

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AnalyticsActivity : AppCompatActivity() {
    
    private lateinit var analyticsManager: AnalyticsManager
    private lateinit var spinner: Spinner
    private lateinit var contentLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    
    private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        analyticsManager = AnalyticsManager(this)
        
        createUI()
        loadAnalytics()
    }
    
    private fun createUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Title
        val title = TextView(this).apply {
            text = "📊 Race Analytics"
            textSize = 24f
            setTextColor(Color.parseColor("#E94E1B"))
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(title)
        
        // Analytics type spinner
        val spinnerLabel = TextView(this).apply {
            text = "Select Analytics View:"
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }
        rootLayout.addView(spinnerLabel)
        
        spinner = Spinner(this)
        val options = arrayOf(
            "Personal Records",
            "Performance Trends",
            "Weekly Summary",
            "Monthly Summary",
            "Race Comparison",
            "Recent Races"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadAnalytics()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rootLayout.addView(spinner)
        
        // Progress bar
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }
        rootLayout.addView(progressBar)
        
        // Scrollable content
        val scrollView = ScrollView(this)
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }
        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        
        setContentView(rootLayout)
    }
    
    private fun loadAnalytics() {
        contentLayout.removeAllViews()
        progressBar.visibility = View.VISIBLE
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                when (spinner.selectedItemPosition) {
                    0 -> showPersonalRecords()
                    1 -> showPerformanceTrends()
                    2 -> showWeeklySummary()
                    3 -> showMonthlySummary()
                    4 -> showRaceComparison()
                    5 -> showRecentRaces()
                }
            } catch (e: Exception) {
                showError("Error loading analytics: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private suspend fun showPersonalRecords() {
        val records = withContext(Dispatchers.IO) {
            analyticsManager.getPersonalRecords()
        }
        
        if (records == null) {
            addText("No race data available yet. Complete some races to see your personal records!")
            return
        }
        
        addSectionTitle("🏆 Personal Records")
        
        addCard("Fastest Pace", String.format("%.2f min/km", records.fastestPace),
            "on ${dateFormat.format(Date(records.fastestRaceDate))}")
        
        addCard("Longest Distance", String.format("%.2f km", records.longestDistance),
            "on ${dateFormat.format(Date(records.longestRaceDate))}")
        
        addCard("Most Elevation Gain", String.format("%.1f m", records.mostElevationGain),
            "on ${dateFormat.format(Date(records.mostElevationRaceDate))}")
        
        val durationStr = formatDuration(records.longestDuration)
        addCard("Longest Duration", durationStr,
            "on ${dateFormat.format(Date(records.longestDurationRaceDate))}")
    }
    
    private suspend fun showPerformanceTrends() {
        val trend = withContext(Dispatchers.IO) {
            analyticsManager.getPerformanceTrend(10)
        }
        
        if (trend.dates.isEmpty()) {
            addText("No race data available for trends analysis.")
            return
        }
        
        addSectionTitle("📈 Performance Trends (Last 10 Races)")
        
        // Distance trend chart
        createLineChart("Distance Over Time (km)", trend.dates, trend.distances)
        
        // Pace trend chart
        createLineChart("Pace Over Time (min/km)", trend.dates, trend.paces)
        
        // Calories trend chart
        createLineChart("Calories Over Time", trend.dates, trend.calories)
    }
    
    private suspend fun showWeeklySummary() {
        val summary = withContext(Dispatchers.IO) {
            analyticsManager.getWeeklySummary()
        }
        
        if (summary == null) {
            addText("No race data available for this week.")
            return
        }
        
        showPeriodSummary("📅 Weekly Summary", summary)
    }
    
    private suspend fun showMonthlySummary() {
        val summary = withContext(Dispatchers.IO) {
            analyticsManager.getMonthlySummary()
        }
        
        if (summary == null) {
            addText("No race data available for this month.")
            return
        }
        
        showPeriodSummary("📅 Monthly Summary", summary)
    }
    
    private fun showPeriodSummary(title: String, summary: PeriodSummary) {
        addSectionTitle(title)
        
        addCard("Total Races", "${summary.totalRaces}", "")
        addCard("Total Distance", String.format("%.2f km", summary.totalDistance), "")
        addCard("Total Duration", formatDuration(summary.totalDuration), "")
        addCard("Total Elevation Gain", String.format("%.1f m", summary.totalElevationGain), "")
        addCard("Total Calories", String.format("%.0f kcal", summary.totalCalories), "")
        addCard("Average Pace", String.format("%.2f min/km", summary.averagePace), "")
        addCard("Average Speed", String.format("%.2f km/h", summary.averageSpeed), "")
        addCard("Best Pace", String.format("%.2f min/km", summary.bestPace), "")
        addCard("Longest Run", String.format("%.2f km", summary.longestRun), "")
    }
    
    private suspend fun showRaceComparison() {
        val races = withContext(Dispatchers.IO) {
            analyticsManager.getRecentRaces(10)
        }
        
        if (races.size < 2) {
            addText("Need at least 2 races to compare. Complete more races!")
            return
        }
        
        addSectionTitle("⚖️ Race Comparison")
        
        // Compare last two races
        val comparison = withContext(Dispatchers.IO) {
            analyticsManager.compareRaces(races[0].id, races[1].id)
        }
        
        if (comparison != null) {
            addText("Comparing: ${dateFormat.format(Date(comparison.race1.startTime))} vs ${dateFormat.format(Date(comparison.race2.startTime))}")
            
            addComparisonCard("Distance", 
                String.format("%.2f km", comparison.race1.totalDistanceMeters / 1000.0),
                String.format("%.2f km", comparison.race2.totalDistanceMeters / 1000.0),
                String.format("%.2f km", comparison.distanceDiff))
            
            addComparisonCard("Pace",
                String.format("%.2f min/km", comparison.race1.averagePaceMinPerKm),
                String.format("%.2f min/km", comparison.race2.averagePaceMinPerKm),
                String.format("%.2f min/km", comparison.paceDiff))
            
            addComparisonCard("Duration",
                formatDuration(comparison.race1.duration),
                formatDuration(comparison.race2.duration),
                formatDuration(comparison.durationDiff))
            
            addComparisonCard("Elevation Gain",
                String.format("%.1f m", comparison.race1.elevationGainMeters),
                String.format("%.1f m", comparison.race2.elevationGainMeters),
                String.format("%.1f m", comparison.elevationDiff))
            
            addComparisonCard("Calories",
                String.format("%.0f kcal", comparison.race1.caloriesBurned),
                String.format("%.0f kcal", comparison.race2.caloriesBurned),
                String.format("%.0f kcal", comparison.caloriesDiff))
        }
    }
    
    private suspend fun showRecentRaces() {
        val races = withContext(Dispatchers.IO) {
            analyticsManager.getRecentRaces(10)
        }
        
        if (races.isEmpty()) {
            addText("No race data available yet.")
            return
        }
        
        addSectionTitle("🏃 Recent Races")
        
        for ((index, race) in races.withIndex()) {
            val card = createCardView()
            
            val title = TextView(this).apply {
                text = "Race #${races.size - index} - ${dateFormat.format(Date(race.startTime))}"
                textSize = 18f
                setTextColor(Color.parseColor("#E94E1B"))
                setPadding(0, 0, 0, 10)
            }
            card.addView(title)
            
            addInfoRow(card, "Distance", String.format("%.2f km", race.totalDistanceMeters / 1000.0))
            addInfoRow(card, "Duration", formatDuration(race.duration))
            addInfoRow(card, "Avg Pace", String.format("%.2f min/km", race.averagePaceMinPerKm))
            addInfoRow(card, "Avg Speed", String.format("%.2f km/h", race.averageSpeedKmh))
            addInfoRow(card, "Elevation Gain", String.format("%.1f m", race.elevationGainMeters))
            addInfoRow(card, "Calories", String.format("%.0f kcal", race.caloriesBurned))
            
            // Add splits info
            if (race.splitTimes.isNotEmpty()) {
                val splitsText = TextView(this).apply {
                    text = "\nSplits (${race.splitTimes.size}):"
                    textSize = 14f
                    setPadding(0, 10, 0, 5)
                }
                card.addView(splitsText)
                
                race.splitTimes.forEachIndexed { idx, time ->
                    val pace = if (idx < race.splitPaces.size) race.splitPaces[idx] else 0.0
                    addInfoRow(card, "  Km ${idx + 1}", 
                        "${formatDuration(time)} (${String.format("%.2f min/km", pace)})")
                }
            }
            
            contentLayout.addView(card)
        }
    }
    
    // ===== UI HELPER METHODS =====
    
    private fun addSectionTitle(title: String) {
        val textView = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor("#E94E1B"))
            setPadding(0, 20, 0, 20)
        }
        contentLayout.addView(textView)
    }
    
    private fun addText(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(10, 10, 10, 10)
        }
        contentLayout.addView(textView)
    }
    
    private fun addCard(title: String, value: String, subtitle: String) {
        val card = createCardView()
        
        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        card.addView(titleView)
        
        val valueView = TextView(this).apply {
            text = value
            textSize = 24f
            setTextColor(Color.parseColor("#E94E1B"))
        }
        card.addView(valueView)
        
        if (subtitle.isNotEmpty()) {
            val subtitleView = TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.GRAY)
            }
            card.addView(subtitleView)
        }
        
        contentLayout.addView(card)
    }
    
    private fun addComparisonCard(metric: String, value1: String, value2: String, diff: String) {
        val card = createCardView()
        
        val titleView = TextView(this).apply {
            text = metric
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        }
        card.addView(titleView)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val race1View = TextView(this).apply {
            text = "Race 1: $value1"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(race1View)
        
        val race2View = TextView(this).apply {
            text = "Race 2: $value2"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(race2View)
        
        card.addView(row)
        
        val diffView = TextView(this).apply {
            text = "Difference: $diff"
            textSize = 14f
            setTextColor(Color.parseColor("#E94E1B"))
            setPadding(0, 10, 0, 0)
        }
        card.addView(diffView)
        
        contentLayout.addView(card)
    }
    
    private fun createCardView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
        }
    }
    
    private fun addInfoRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5, 0, 5)
        }
        
        val labelView = TextView(this).apply {
            text = "$label: "
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        row.addView(labelView)
        
        val valueView = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.BLACK)
        }
        row.addView(valueView)
        
        parent.addView(row)
    }
    
    private fun createLineChart(title: String, dates: List<Long>, values: List<Double>) {
        val chartTitle = TextView(this).apply {
            text = title
            textSize = 16f
            setPadding(0, 20, 0, 10)
        }
        contentLayout.addView(chartTitle)
        
        val chart = LineChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
        }
        
        val entries = values.mapIndexed { index, value ->
            Entry(index.toFloat(), value.toFloat())
        }
        
        val dataSet = LineDataSet(entries, title).apply {
            color = Color.parseColor("#E94E1B")
            setCircleColor(Color.parseColor("#E94E1B"))
            lineWidth = 2f
            circleRadius = 4f
            setDrawValues(false)
        }
        
        val lineData = LineData(dataSet)
        chart.data = lineData
        
        // X-axis configuration
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index in dates.indices) {
                        dateFormat.format(Date(dates[index]))
                    } else ""
                }
            }
            granularity = 1f
            setDrawGridLines(false)
        }
        
        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        
        chart.invalidate()
        contentLayout.addView(chart)
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
}
