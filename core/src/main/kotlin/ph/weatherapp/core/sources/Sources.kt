package ph.weatherapp.core.sources

import kotlinx.serialization.json.*
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.model.HourlyPoint
import ph.weatherapp.core.model.SourceForecast
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Readers for free gridded forecast sources. Each one builds the request URL
 * and turns the raw JSON into the common [SourceForecast] the engine uses.
 * Fetching (network) happens in the app; parsing lives here so it can be tested.
 *
 * Before charging money, re-check each source's terms:
 *  - Open-Meteo's free API is for NON-COMMERCIAL use; a paid plan is needed for a paid app.
 *  - MET Norway is free (CC BY 4.0) but requires a User-Agent that identifies the app, and attribution.
 */
interface WeatherSource {
    val id: String
    fun url(lat: Double, lon: Double): String
    fun parse(json: String, fetchedAt: ZonedDateTime): SourceForecast
}

object OpenMeteo : WeatherSource {
    override val id = "open-meteo"

    override fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,relative_humidity_2m,precipitation_probability,wind_speed_10m,wind_gusts_10m" +
            "&wind_speed_unit=kmh&timezone=Asia%2FManila&forecast_days=3"

    override fun parse(json: String, fetchedAt: ZonedDateTime): SourceForecast {
        val hourly = Json.parseToJsonElement(json).jsonObject["hourly"]?.jsonObject
            ?: error("Open-Meteo: no 'hourly' block")
        val times = hourly.arr("time").map { LocalDateTime.parse(it.jsonPrimitive.content).atZone(MANILA) }
        val temp = hourly.arr("temperature_2m")
        val hum = hourly.arr("relative_humidity_2m")
        val rain = hourly.arr("precipitation_probability")
        val wind = hourly.arr("wind_speed_10m")
        val gust = hourly.arr("wind_gusts_10m")
        val points = times.indices.map { i ->
            HourlyPoint(
                time = times[i],
                rainChancePct = rain.num(i)?.toInt(),
                tempC = temp.num(i),
                windKph = wind.num(i),
                gustKph = gust.num(i),
                humidityPct = hum.num(i)?.toInt(),
            )
        }
        return SourceForecast(id, fetchedAt, points)
    }
}

object MetNorway : WeatherSource {
    override val id = "met-norway"
    /** MET Norway blocks requests without an identifying User-Agent. */
    const val USER_AGENT = "WeatherAlarmPH/0.1 github.com/dansalasim/weather-alarm-app"

    override fun url(lat: Double, lon: Double) =
        "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${"%.4f".format(lat)}&lon=${"%.4f".format(lon)}"

    override fun parse(json: String, fetchedAt: ZonedDateTime): SourceForecast {
        val series = Json.parseToJsonElement(json).jsonObject["properties"]?.jsonObject
            ?.get("timeseries")?.jsonArray ?: error("MET Norway: no 'timeseries'")
        val points = series.map { el ->
            val o = el.jsonObject
            val data = o["data"]!!.jsonObject
            val d = data["instant"]!!.jsonObject["details"]!!.jsonObject
            // Rain probability is only given in some regions; use 1-hour, then 6-hour, else leave empty.
            val pop = listOf("next_1_hours", "next_6_hours").firstNotNullOfOrNull { k ->
                data[k]?.jsonObject?.get("details")?.jsonObject?.get("probability_of_precipitation")?.jsonPrimitive?.doubleOrNull
            }
            HourlyPoint(
                time = ZonedDateTime.parse(o["time"]!!.jsonPrimitive.content).withZoneSameInstant(MANILA),
                rainChancePct = pop?.toInt(),
                tempC = d.dbl("air_temperature"),
                windKph = d.dbl("wind_speed")?.times(3.6),          // m/s → km/h
                gustKph = d.dbl("wind_speed_of_gust")?.times(3.6),
                humidityPct = d.dbl("relative_humidity")?.toInt(),
            )
        }
        return SourceForecast(id, fetchedAt, points)
    }
}

/**
 * A global model's ENSEMBLE via Open-Meteo: the model is run many times with slightly different
 * starting conditions. Rain chance = share of runs with at least [rainThresholdMm] of rain that hour.
 * Temperature, humidity and wind come from the control run.
 * These are the same models Windy displays (ECMWF is Windy's default).
 */
class OpenMeteoEnsemble(
    override val id: String,
    private val model: String,
    private val rainThresholdMm: Double = 0.1,
) : WeatherSource {

    override fun url(lat: Double, lon: Double) =
        "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=$lat&longitude=$lon&models=$model" +
            "&hourly=precipitation,temperature_2m,relative_humidity_2m,wind_speed_10m,wind_gusts_10m" +
            "&wind_speed_unit=kmh&timezone=Asia%2FManila&forecast_days=3"

    override fun parse(json: String, fetchedAt: ZonedDateTime): SourceForecast {
        val hourly = Json.parseToJsonElement(json).jsonObject["hourly"]?.jsonObject
            ?: error("$id: no 'hourly' block")
        val times = hourly.arr("time").map { LocalDateTime.parse(it.jsonPrimitive.content).atZone(MANILA) }
        // "precipitation" (control) plus "precipitation_member01" … "precipitation_memberNN"
        val precipRuns = hourly.keys.filter { it == "precipitation" || it.startsWith("precipitation_member") }
            .map { hourly.arr(it) }
        val temp = hourly.arr("temperature_2m")
        val hum = hourly.arr("relative_humidity_2m")
        val wind = hourly.arr("wind_speed_10m")
        val gust = hourly.arr("wind_gusts_10m")
        val points = times.indices.map { i ->
            val values = precipRuns.mapNotNull { it.num(i) }
            HourlyPoint(
                time = times[i],
                rainChancePct = if (values.isEmpty()) null
                    else Math.round(100.0 * values.count { it >= rainThresholdMm } / values.size).toInt(),
                tempC = temp.num(i),
                windKph = wind.num(i),
                gustKph = gust.num(i),
                humidityPct = hum.num(i)?.toInt(),
            )
        }
        return SourceForecast(id, fetchedAt, points)
    }
}

val EcmwfEnsemble = OpenMeteoEnsemble("ecmwf", "ecmwf_ifs025")
val GfsEnsemble = OpenMeteoEnsemble("gfs", "gfs025")

val ALL_SOURCES: List<WeatherSource> = listOf(EcmwfEnsemble, GfsEnsemble, OpenMeteo, MetNorway)

private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())
private fun JsonArray.num(i: Int): Double? = getOrNull(i)?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }
private fun JsonObject.dbl(k: String): Double? = this[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }
