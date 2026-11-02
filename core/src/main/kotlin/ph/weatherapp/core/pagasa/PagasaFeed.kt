package ph.weatherapp.core.pagasa

import ph.weatherapp.core.model.PagasaAdvisory
import ph.weatherapp.core.model.RainfallWarning
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * One area's PAGASA data, in the shape the server sends to the app.
 * The fields mirror what CDRRMO Isabela City already extracts every morning:
 *   - Mindanao PRSD Regional Forecast (5:00 AM)   → [RegionalForecast]
 *   - DOST-PAGASA iHeatMAP, barangay level        → [HeatIndexForecast]
 *   - Warnings (rainfall, thunderstorm, Signal)   → [Warnings]
 * See samples/pagasa-feed-2026-10-28.json for a real example.
 */
data class PagasaFeed(
    val areaId: String,                       // e.g. "isabela-city-basilan"
    val regional: RegionalForecast?,
    val heatIndex: HeatIndexForecast?,
    val warnings: Warnings,
    val updatedAt: ZonedDateTime,
)

data class RegionalForecast(
    val issuedAt: ZonedDateTime,              // PAGASA issues at 5:00 AM
    val validUntil: ZonedDateTime,            // 5:00 AM next day
    val skyCondition: String,                 // PAGASA's own words
    val tempMinC: Int?,
    val tempMaxC: Int?,
    val winds: String?,                       // "Light to Moderate, from Northeast to East"
    val coastalWaters: String?,               // "Slight to Moderate (0.6–2.1 m)"
    val synopsis: String?,                    // "Trough of LPA"
    val tonightSkyCondition: String? = null,  // 5 PM–5 AM row of the same PDF
    val tonightMinC: Int? = null,
    val tonightMaxC: Int? = null,
)

data class HeatIndexForecast(
    val issuedAt: ZonedDateTime,
    val location: String,                     // "Brgy. Sunrise Village, City of Isabela"
    val date: LocalDate,
    val hourlyC: Map<Int, Int>,               // hour (8..17) → heat index °C
) {
    val peakC: Int? get() = hourlyC.values.maxOrNull()
    val peakHour: Int? get() = hourlyC.maxByOrNull { it.value }?.key
}

data class Warnings(
    val tcwsLevel: Int? = null,
    val rainfallWarning: RainfallWarning? = null,
    val thunderstormAdvisory: Boolean = false,
    val validUntil: ZonedDateTime? = null,
)

/** PAGASA's sky-condition wording, reduced to how likely rain is. */
enum class RainOutlook { UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY }

object PagasaText {

    /**
     * Reads PAGASA's standard forecast phrases. Order matters: the strongest wording is checked first.
     *   "Cloudy skies with moderate to heavy rains"                          → VERY_LIKELY
     *   "Cloudy skies with rainshowers and thunderstorms"                    → LIKELY
     *   "Partly cloudy skies to at times cloudy with rainshowers or thunderstorms" → LIKELY
     *   "Partly cloudy to cloudy skies with isolated rainshowers or thunderstorms" → POSSIBLE
     *   "...with localized thunderstorms" / "isolated light rains"           → POSSIBLE
     *   "Partly cloudy to cloudy skies" (no rain words)                     → UNLIKELY
     */
    fun rainOutlook(skyCondition: String): RainOutlook {
        val s = skyCondition.lowercase()
        val rainWords = listOf("rain", "shower", "thunderstorm", "drizzle")
        return when {
            rainWords.none { it in s } -> RainOutlook.UNLIKELY
            "heavy" in s || "intense" in s || "torrential" in s -> RainOutlook.VERY_LIKELY
            "isolated" in s || "localized" in s || "localised" in s -> RainOutlook.POSSIBLE
            else -> RainOutlook.LIKELY   // "rainshowers and/or thunderstorms", "scattered", "light rains"
        }
    }
}

/**
 * Converts the feed into the advisory the Consolidator uses.
 * POSSIBLE leaves forecastRainLikely empty: PAGASA isn't committing either way,
 * so the other sources decide.
 */
fun PagasaFeed.toAdvisory(): PagasaAdvisory {
    val outlook = regional?.let { PagasaText.rainOutlook(it.skyCondition) }
    return PagasaAdvisory(
        issuedAt = listOfNotNull(regional?.issuedAt, heatIndex?.issuedAt).minOrNull() ?: updatedAt,
        validUntil = warnings.validUntil ?: regional?.validUntil,
        tcwsLevel = warnings.tcwsLevel,
        rainfallWarning = warnings.rainfallWarning,
        thunderstormAdvisory = warnings.thunderstormAdvisory,
        heatIndexForecastC = heatIndex?.peakC,
        forecastRainLikely = when (outlook) {
            RainOutlook.LIKELY, RainOutlook.VERY_LIKELY -> true
            RainOutlook.UNLIKELY -> false
            RainOutlook.POSSIBLE, null -> null
        },
    )
}
