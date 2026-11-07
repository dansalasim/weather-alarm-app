package ph.weatherapp.core.model

import java.time.ZonedDateTime

/**
 * One hour of forecast data from a single source, already converted
 * to common units: % for rain chance, °C for temperature, km/h for wind.
 * Any field may be null when a source doesn't provide it.
 */
data class HourlyPoint(
    val time: ZonedDateTime,
    val rainChancePct: Int?,
    val tempC: Double?,
    val windKph: Double?,
    val gustKph: Double?,
    val humidityPct: Int?,
    /** Rain (mm) in this hour for each ensemble run, when the source is an ensemble. */
    val memberPrecipMm: List<Double>? = null,
)

/** All hourly points from one provider (Open-Meteo, MET Norway, OpenWeatherMap...). */
data class SourceForecast(
    val sourceId: String,
    val fetchedAt: ZonedDateTime,
    val points: List<HourlyPoint>,
    /** False when this source's rain chance shouldn't be counted (e.g. it repeats another source). */
    val rainUsable: Boolean = true,
)

/** PAGASA rainfall warning colors (Heavy Rainfall Warning system). */
enum class RainfallWarning { YELLOW, ORANGE, RED }

/** PAGASA heat index danger categories. */
enum class HeatIndexLevel { CAUTION, EXTREME_CAUTION, DANGER, EXTREME_DANGER }

/**
 * What PAGASA says for the user's area. These are the "big calls" that
 * win whenever the gridded sources disagree or contradict them.
 */
data class PagasaAdvisory(
    val issuedAt: ZonedDateTime,
    val validUntil: ZonedDateTime?,
    val tcwsLevel: Int? = null,                 // Tropical Cyclone Wind Signal 1–5
    val rainfallWarning: RainfallWarning? = null,
    val thunderstormAdvisory: Boolean = false,
    val heatIndexForecastC: Int? = null,         // PAGASA forecast heat index for the area
    val forecastRainLikely: Boolean? = null,     // from the regional text forecast, if parsed
    val skyCondition: String? = null,            // PAGASA's own words, shown on the card
) {
    fun isValidAt(t: ZonedDateTime): Boolean =
        !t.isBefore(issuedAt.minusHours(1)) && (validUntil == null || !t.isAfter(validUntil))
}

enum class WindowKind { DAY, NIGHT }   // DAY = 6 AM–6 PM, NIGHT = 6 PM–6 AM

data class ForecastWindow(
    val kind: WindowKind,
    val start: ZonedDateTime,
    val end: ZonedDateTime,            // exclusive
    val sixHour: Boolean = false,      // premium 6-hour block (vs a 12-hour window)
) {
    /** Hour the full block/window starts at, even when [start] was moved forward to "now". */
    val nominalStartHour: Int get() = end.minusHours(if (sixHour) 6 else 12).hour
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** The consolidated result shown on the forecast screen and the alarm card. */
data class WindowForecast(
    val window: ForecastWindow,
    val rainChancePct: Int,
    val tempMinC: Int,
    val tempMaxC: Int,
    val windMaxKph: Int,
    val gustMaxKph: Int?,
    val heatIndexMaxC: Int?,
    val confidence: Confidence,
    val sourcesUsed: List<String>,
    val pagasaOverrides: List<String>,   // human-readable notes on what PAGASA changed
    val pagasa: PagasaAdvisory?,
)
