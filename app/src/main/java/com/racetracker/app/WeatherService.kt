package com.racetracker.app

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenWeatherApi {
    @GET("weather")
    suspend fun current(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): CurrentWeatherResponse
}

data class CurrentWeatherResponse(
    val coord: Coord,
    val weather: List<Weather>,
    val main: Main,
    val wind: Wind,
    val clouds: Clouds,
    val name: String
)

data class Coord(val lon: Double, val lat: Double)
data class Weather(val id: Int, val main: String, val description: String, val icon: String)
data class Main(
    val temp: Double,
    val feels_like: Double,
    val temp_min: Double,
    val temp_max: Double,
    val pressure: Int,
    val humidity: Int
)
data class Wind(val speed: Double, val deg: Int)
data class Clouds(val all: Int)

object WeatherService {
    private const val BASE_URL = "https://api.openweathermap.org/data/2.5/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val api: OpenWeatherApi = retrofit.create(OpenWeatherApi::class.java)

    fun heatIndex(tempC: Double, humidity: Int): Double {
        val tF = tempC * 9.0/5.0 + 32.0
        val hiF = -42.379 + 2.04901523*tF + 10.14333127*humidity - 0.22475541*tF*humidity -
                6.83783e-3*tF*tF - 5.481717e-2*humidity*humidity + 1.22874e-3*tF*tF*humidity +
                8.5282e-4*tF*humidity*humidity - 1.99e-6*tF*tF*humidity*humidity
        return (hiF - 32.0) * 5.0 / 9.0
    }
}