package ph.weatherapp.core.pagasa

import kotlinx.serialization.json.*
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.model.ForecastWindow
import ph.weatherapp.core.model.PagasaAdvisory
import ph.weatherapp.core.model.RainfallWarning
import ph.weatherapp.core.model.WindowKind
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
fun PagasaFeed.toAdvisory(window: ForecastWindow? = null): PagasaAdvisory {
    // Night windows use PAGASA's "tonight" wording when it's available.
    val sky = if (window?.kind == WindowKind.NIGHT) regional?.tonightSkyCondition ?: regional?.skyCondition
              else regional?.skyCondition
    val outlook = sky?.let { PagasaText.rainOutlook(it) }
    // Heat index only for a daytime window on the same date, and only the hours inside it
    // (so "rest of today" at 3 PM doesn't show the noon peak that has already passed).
    val heat = when {
        heatIndex == null -> null
        window == null -> heatIndex.peakC
        window.kind != WindowKind.DAY || heatIndex.date != window.start.toLocalDate() -> null
        else -> {
            val endHour = if (window.end.hour == 0) 24 else window.end.hour
            heatIndex.hourlyC.filterKeys { it >= window.start.hour && it < endHour }.values.maxOrNull()
        }
    }
    return PagasaAdvisory(
        issuedAt = listOfNotNull(regional?.issuedAt, heatIndex?.issuedAt).minOrNull() ?: updatedAt,
        validUntil = warnings.validUntil ?: regional?.validUntil,
        tcwsLevel = warnings.tcwsLevel,
        rainfallWarning = warnings.rainfallWarning,
        thunderstormAdvisory = warnings.thunderstormAdvisory,
        heatIndexForecastC = heat,
        skyCondition = sky,
        forecastRainLikely = when (outlook) {
            RainOutlook.LIKELY, RainOutlook.VERY_LIKELY -> true
            RainOutlook.UNLIKELY -> false
            RainOutlook.POSSIBLE, null -> null
        },
    )
}

/**
 * Reads the two daily feed files the CDRRMO morning tasks publish
 * (feed/<area>/regional.json and feed/<area>/heat.json). Either may be missing.
 * Bad or missing fields become null rather than failing the whole feed.
 */
object PagasaFeedJson {

    fun parse(areaId: String, regionalJson: String?, heatJson: String?, now: ZonedDateTime): PagasaFeed {
        val r = regionalJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val h = heatJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

        val regional = r?.let { o ->
            val issued = o.time("issuedAt") ?: return@let null
            RegionalForecast(
                issuedAt = issued,
                validUntil = o.time("validUntil") ?: issued.plusDays(1),
                skyCondition = o.str("skyCondition") ?: return@let null,
                tempMinC = o.int("tempMinC"),
                tempMaxC = o.int("tempMaxC"),
                winds = o.str("winds"),
                coastalWaters = o.str("coastalWaters"),
                synopsis = o.str("synopsis"),
                tonightSkyCondition = o.str("tonightSkyCondition"),
                tonightMinC = o.int("tonightMinC"),
                tonightMaxC = o.int("tonightMaxC"),
            )
        }

        val w = r?.get("warnings")?.let { it as? JsonObject }
        val warnings = Warnings(
            tcwsLevel = w?.int("tcwsLevel")?.takeIf { it in 1..5 },
            rainfallWarning = w?.str("rainfallWarning")?.uppercase()?.let { c -> RainfallWarning.entries.firstOrNull { it.name == c } },
            thunderstormAdvisory = w?.bool("thunderstormAdvisory") ?: false,
            validUntil = w?.time("validUntil"),
        )

        val heat = h?.let { o ->
            val hourly = (o["hourlyC"] as? JsonObject)?.mapNotNull { (k, v) ->
                val hour = k.toIntOrNull(); val c = (v as? JsonPrimitive)?.doubleOrNull
                if (hour != null && c != null) hour to Math.round(c).toInt() else null
            }?.toMap().orEmpty()
            val date = o.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (hourly.isEmpty() || date == null) null else HeatIndexForecast(
                issuedAt = o.time("issuedAt") ?: now,
                location = o.str("location") ?: "",
                date = date,
                hourlyC = hourly,
            )
        }

        return PagasaFeed(
            areaId = areaId,
            regional = regional,
            heatIndex = heat,
            warnings = warnings,
            updatedAt = listOfNotNull(regional?.issuedAt, heat?.issuedAt).maxOrNull() ?: now,
        )
    }

    private fun JsonObject.prim(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String) = prim(k)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.int(k: String) = prim(k)?.doubleOrNull?.let { Math.round(it).toInt() }
    private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
    private fun JsonObject.time(k: String) = str(k)?.let {
        runCatching { ZonedDateTime.parse(it).withZoneSameInstant(MANILA) }.getOrNull()
    }
}
