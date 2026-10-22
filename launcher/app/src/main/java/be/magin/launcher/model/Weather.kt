package be.magin.launcher.model

import be.magin.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

data class DayForecast(val day: String, val code: Int, val max: Int)

data class Weather(
    val city: String,
    val temperature: Int,
    val code: Int,
    val isDay: Boolean,
    val min: Int,
    val max: Int,
    val days: List<DayForecast>,
)

/** Open-Meteo (no API key), located by IP; falls back to Brussels. */
object WeatherRepository {
    private const val FALLBACK_LAT = 50.8503
    private const val FALLBACK_LON = 4.3517
    private const val FALLBACK_CITY = "Bruxelles"

    suspend fun fetch(): Weather = withContext(Dispatchers.IO) {
        val (lat, lon, city) = runCatching {
            val geo = JSONObject(get("https://get.geojs.io/v1/ip/geo.json"))
            Triple(
                geo.getString("latitude").toDouble(),
                geo.getString("longitude").toDouble(),
                geo.optString("city").ifBlank { FALLBACK_CITY }.let { if (it == "Brussels") FALLBACK_CITY else it },
            )
        }.getOrDefault(Triple(FALLBACK_LAT, FALLBACK_LON, FALLBACK_CITY))

        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=4",
            ),
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        Weather(
            city = city,
            temperature = current.getDouble("temperature_2m").roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            min = mins.getDouble(0).roundToInt(),
            max = maxs.getDouble(0).roundToInt(),
            days = (1 until minOf(4, dates.length())).map { i ->
                DayForecast(dayLabel(LocalDate.parse(dates.getString(i))), codes.getInt(i), maxs.getDouble(i).roundToInt())
            },
        )
    }

    /** "ven." → "Ven" */
    private fun dayLabel(date: LocalDate) =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH).trimEnd('.').replaceFirstChar { it.titlecase(Locale.FRENCH) }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** WMO weather code → Phosphor icon. */
fun weatherIcon(code: Int, isDay: Boolean = true): Int = when (code) {
    0 -> if (isDay) R.drawable.ph_sun else R.drawable.ph_moon
    1, 2 -> if (isDay) R.drawable.ph_cloud_sun else R.drawable.ph_cloud_moon
    45, 48 -> R.drawable.ph_cloud_fog
    in 51..67, in 80..82 -> R.drawable.ph_cloud_rain
    in 71..77, 85, 86 -> R.drawable.ph_cloud_snow
    in 95..99 -> R.drawable.ph_cloud_lightning
    else -> R.drawable.ph_cloud
}
