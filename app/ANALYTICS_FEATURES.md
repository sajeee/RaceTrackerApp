# Race Tracker Analytics Features

## Overview
This document describes the new analytics features added to the Race Tracker Android app.

## New Features Added

### 1. **Personal Records** 🏆
Track and display your best performances across all races:
- **Fastest Pace**: Your best average pace (min/km)
- **Longest Distance**: Your longest run distance (km)
- **Most Elevation Gain**: Your biggest elevation climb (meters)
- **Longest Duration**: Your longest race time

Each record includes the date it was achieved.

### 2. **Race Comparison** ⚖️
Compare two races side-by-side to see your progress:
- Distance comparison
- Pace comparison
- Duration comparison
- Elevation gain comparison
- Calories burned comparison

Shows the difference between races to help track improvement.

### 3. **Performance Trends** 📈
Visualize your performance over time with interactive charts:
- Distance over time
- Pace improvements
- Calories burned trends
- Speed progression

Charts show your last 10 races by default.

### 4. **Split Times Analysis** ⏱️
Detailed breakdown of each kilometer:
- Time for each km split
- Pace for each split (min/km)
- Speed variation per split
- Elevation change per split
- Comparison to average pace

### 5. **Calories Burned Estimation** 🔥
Accurate calorie calculation based on:
- Distance covered
- Duration of activity
- User weight (currently default 70kg)
- Speed/intensity
- Elevation gain bonus

Formula adjusts MET (Metabolic Equivalent) values based on running speed and adds bonus calories for elevation climbs.

### 6. **Heart Rate Zones** ❤️
(Ready for integration when heart rate data is available)
- 5 heart rate zones (Very Light to Maximum)
- Time spent in each zone
- Percentage distribution
- Zone-based training insights

### 7. **Weekly/Monthly Summaries** 📅
Comprehensive period statistics:
- Total number of races
- Total distance covered
- Total duration
- Total elevation gain
- Total calories burned
- Average pace
- Average speed
- Best pace
- Longest single run

## Technical Implementation

### New Files Created

#### 1. **RaceData.kt**
Data models for race storage:
- `RaceData`: Main race entity with all metrics
- `PersonalRecords`: PR data structure
- `RaceComparison`: Comparison results
- `PerformanceTrend`: Trend data
- `PeriodSummary`: Weekly/monthly stats
- `HeartRateZone`: HR zone analysis
- `SplitAnalysis`: Detailed split breakdown
- `Converters`: Room database type converters

#### 2. **RaceDao.kt**
Database access interface with queries for:
- Insert/update/delete races
- Retrieve races by various criteria
- Get personal records (fastest, longest, etc.)
- Time-range queries
- Aggregate statistics

#### 3. **RaceDatabase.kt**
Room database singleton:
- Thread-safe database instance
- Automatic schema management
- Type converters for complex data

#### 4. **AnalyticsManager.kt**
Core analytics engine providing:
- Race data saving
- Personal records calculation
- Race comparison logic
- Performance trend generation
- Period summary calculation
- Split times analysis
- Calories estimation
- Heart rate zone calculation

#### 5. **AnalyticsActivity.kt**
User interface for analytics:
- Spinner for selecting analytics view
- Dynamic UI generation
- Chart rendering with MPAndroidChart
- Card-based information display
- Scrollable content area

#### 6. **LiveTrackerActivityExtension.kt**
Extension functions to integrate analytics:
- Automatic race saving on stop
- Data collection during tracking
- Timestamp generation
- Speed estimation

### Updated Files

#### 1. **build.gradle**
Added dependencies:
- Room Database (runtime, ktx, compiler)
- Gson for JSON serialization
- Kotlin kapt plugin

#### 2. **LiveTrackerActivity.kt**
Integrated analytics saving:
- Calls `saveRaceToAnalytics()` when race stops
- Automatically persists race data to database

#### 3. **SetupActivity.kt**
Added analytics button:
- New "View Analytics" button
- Opens AnalyticsActivity

#### 4. **AndroidManifest.xml**
Registered new activity:
- AnalyticsActivity entry with portrait orientation

#### 5. **activity_setup.xml**
Added UI element:
- Analytics button with Material Design styling

## How It Works

### Data Flow

1. **During Race**:
   - TrackingService collects location data
   - LiveTrackerActivity receives updates
   - Path points, elevations, and speeds are accumulated

2. **Race Stop**:
   - User clicks Stop button
   - `saveRaceToAnalytics()` is triggered
   - AnalyticsManager calculates all metrics
   - Data is saved to Room database

3. **Viewing Analytics**:
   - User opens Analytics from Setup screen
   - AnalyticsActivity queries database
   - Data is processed and displayed
   - Charts are generated for trends

### Database Schema

**races** table stores:
- Race metadata (IDs, times, duration)
- Performance metrics (distance, speed, pace)
- Elevation data (gain, loss, max, min)
- Physiological data (calories, HR)
- GPS path (serialized LatLng points)
- Split times and paces
- Optional notes

### Calculations

#### Distance Calculation
Uses Haversine formula for accurate GPS distance:
```
R = 6371000 (Earth radius in meters)
distance = R * 2 * atan2(√a, √(1-a))
where a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlon/2)
```

#### Pace Calculation
```
pace (min/km) = 60 / speed (km/h)
```

#### Split Times
Automatically splits race into 1km segments with:
- Individual split time
- Split pace
- Pace variation from average

#### Calories
Uses MET (Metabolic Equivalent of Task) formula:
```
calories = MET * weight(kg) * duration(hours)
MET varies from 6 (slow) to 14.5 (sprint)
+ bonus for elevation: (elevation_meters / 10) * weight * 0.1
```

## Usage

### Viewing Analytics

1. Open the Race Tracker app
2. On Setup screen, tap "📊 View Analytics"
3. Select analytics type from dropdown:
   - Personal Records
   - Performance Trends
   - Weekly Summary
   - Monthly Summary
   - Race Comparison
   - Recent Races

### Understanding Your Data

**Personal Records**: Shows your all-time bests
**Trends**: Identify improvement or areas needing work
**Summaries**: Track weekly/monthly training volume
**Comparison**: Compare any two races directly
**Splits**: Analyze pacing strategy and consistency

## Future Enhancements

### Potential Additions
1. **Custom time periods**: Select specific date ranges
2. **Export data**: CSV/PDF export functionality
3. **Social sharing**: Share achievements
4. **Goal tracking**: Set and monitor goals
5. **Training plans**: Structured workout plans
6. **Shoe tracking**: Monitor shoe mileage
7. **Weather correlation**: Impact of weather on performance
8. **Route replay**: Animated route playback
9. **Voice coaching**: Real-time audio feedback
10. **Integration**: Sync with Strava, Garmin, etc.

### Heart Rate Integration
When heart rate data becomes available:
1. Update `saveRace()` to include HR data
2. Calculate HR zones automatically
3. Display HR trends in analytics
4. Add HR-based training insights

### User Settings
Add preferences for:
- User weight (for accurate calories)
- Max heart rate
- Training zones
- Units (metric/imperial)
- Chart preferences

## Performance Considerations

### Database
- Room database is efficient for this use case
- Indices on commonly queried fields
- Asynchronous operations with coroutines
- No impact on race tracking performance

### Memory
- Charts load only displayed data
- Large races split efficiently
- Type converters optimize storage
- No memory leaks in activity lifecycle

### Storage
- Each race ~10-50KB depending on length
- 1000 races = ~10-50MB
- GPS coordinates efficiently serialized
- Old races can be archived/deleted

## Testing Recommendations

1. **Complete a race**: Ensure data is saved
2. **View analytics**: Check all views load
3. **Multiple races**: Verify trends and comparisons
4. **Edge cases**: Very short/long races
5. **Performance**: Large number of races
6. **Charts**: Proper scaling and labels

## Dependencies Added

```gradle
// Room Database
implementation "androidx.room:room-runtime:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"

// Gson
implementation 'com.google.code.gson:gson:2.10.1'

// Already present:
// MPAndroidChart for charting
// Kotlin Coroutines for async operations
```

## Troubleshooting

### Data not saving
- Check logcat for errors
- Verify database initialization
- Ensure sufficient storage space

### Charts not displaying
- Verify MPAndroidChart dependency
- Check if data is available
- Look for chart rendering errors

### Performance issues
- Consider limiting trend history
- Optimize chart update frequency
- Profile with Android Profiler

## Code Quality

All new code follows:
- Kotlin coding conventions
- Android architecture best practices
- SOLID principles
- Proper error handling
- Comprehensive documentation
- Type safety with Kotlin

## Credits

Analytics implementation integrates seamlessly with existing:
- Google Maps SDK
- Material Design components
- Android Architecture Components
- Existing tracking infrastructure

---

**Version**: 1.0  
**Last Updated**: 2025-11-17  
**Author**: Race Tracker Analytics Team
