package ph.weatherapp.core.engine

import ph.weatherapp.core.model.*
import kotlin.math.roundToInt

/**
 * Turns several sources' hourly data into one 12-hour window forecast.
 *
 * Steps:
 *  1. For each source, summarize its hours inside the window. Rain chance for an ensemble =
 *     share of runs with at least [Config.blockRainMm] of rain in the WHOLE block (so drizzle
 *     and single wet hours don't count); other sources use their highest hourly chance.
 *  2. Combine sources with the MEDIAN, so one wild source can't drag the result.
 *  3. Measure how much the sources agree → confidence.
 *  4. Apply PAGASA: warnings set minimum values, and when the sources
 *     disagree badly, PAGASA's own forecast decides.
 */
class Consolidator(private val cfg: Config = Config()) {

    data class Config(
        val minHoursPerSource: Int = 6,     // ignore a source covering < half the window
        val blockRainMm: Double = 2.0,      // an ensemble run "rains" if it totals this much in the block
        val highAgreementRainSpread: Int = 20,
        val lowAgreementRainSpread: Int = 40,
        val highAgreementTempSpread: Double = 2.0,
        val lowAgreementTempSpread: Double = 4.0,
    )

    private data class SourceSummary(
        val sourceId: String,
        val rainMax: Int?,
        val tempMin: Double?,
        val tempMax: Double?,
        val windMax: Double?,
        val gustMax: Double?,
        val heatIndexMax: Double?,
    )

    fun consolidate(
        window: ForecastWindow,
        sources: List<SourceForecast>,
        pagasa: PagasaAdvisory?,
    ): WindowForecast? {
        val summaries = sources.mapNotNull { summarize(it, window) }
        val advisory = pagasa?.takeIf { it.isValidAt(window.start) || it.isValidAt(window.end.minusHours(1)) }
        if (summaries.isEmpty() && advisory == null) return null

        var rain = median(summaries.mapNotNull { it.rainMax?.toDouble() })?.roundToInt() ?: 0
        val tMin = median(summaries.mapNotNull { it.tempMin })
        val tMax = median(summaries.mapNotNull { it.tempMax })
        var wind = median(summaries.mapNotNull { it.windMax })?.roundToInt() ?: 0
        val gust = median(summaries.mapNotNull { it.gustMax })?.roundToInt()
        var heat = if (window.kind == WindowKind.DAY)
            median(summaries.mapNotNull { it.heatIndexMax })?.roundToInt() else null

        val confidence = confidenceOf(summaries)
        val notes = mutableListOf<String>()

        if (advisory != null) {
            // --- Hard floors from official warnings (always win) ---
            advisory.tcwsLevel?.let { level ->
                val floorWind = tcwsMinWindKph(level)
                if (wind < floorWind) { wind = floorWind; notes += "Wind raised to PAGASA Signal No. $level level" }
                if (rain < 80) { rain = 80; notes += "Rain chance raised: Signal No. $level in effect" }
            }
            advisory.rainfallWarning?.let { w ->
                val floor = when (w) { RainfallWarning.YELLOW -> 70; RainfallWarning.ORANGE -> 85; RainfallWarning.RED -> 95 }
                if (rain < floor) { rain = floor; notes += "Rain chance raised: PAGASA ${w.name.lowercase()} rainfall warning" }
            }
            if (advisory.thunderstormAdvisory && rain < 60) {
                rain = 60; notes += "Rain chance raised: PAGASA thunderstorm advisory"
            }
            // --- Heat index: PAGASA's forecast replaces our computed value ---
            if (window.kind == WindowKind.DAY) advisory.heatIndexForecastC?.let {
                if (heat != it) notes += "Heat index set to PAGASA forecast (${it}°C)"
                heat = it
            }
            // --- Sources disagree badly → PAGASA's text forecast prevails ---
            val noWarnings = advisory.tcwsLevel == null && advisory.rainfallWarning == null && !advisory.thunderstormAdvisory
            if (noWarnings && (confidence == Confidence.LOW || summaries.isEmpty())) {
                when (advisory.forecastRainLikely) {
                    true -> if (rain < 60) { rain = 60; notes += "Sources disagree; following PAGASA (rain likely)" }
                    false -> if (rain > 30) { rain = 30; notes += "Sources disagree; following PAGASA (rain unlikely)" }
                    // PAGASA says rain is only possible ("isolated", "partly cloudy … rainshowers or
                    // thunderstorms"): don't let the models call it a near-certainty.
                    null -> if (advisory.skyCondition != null && rain > 60) {
                        rain = 60; notes += "Sources disagree; following PAGASA (rain possible)"
                    }
                }
            }
        }

        return WindowForecast(
            window = window,
            rainChancePct = rain.coerceIn(0, 100),
            tempMinC = tMin?.roundToInt() ?: tMax?.roundToInt() ?: 0,
            tempMaxC = tMax?.roundToInt() ?: tMin?.roundToInt() ?: 0,
            windMaxKph = wind,
            gustMaxKph = gust,
            heatIndexMaxC = heat,
            confidence = if (summaries.isEmpty()) Confidence.LOW else confidence,
            sourcesUsed = summaries.map { it.sourceId },
            pagasaOverrides = notes,
            pagasa = advisory,
        )
    }

    private fun summarize(src: SourceForecast, w: ForecastWindow): SourceSummary? {
        val pts = src.points.filter { !it.time.isBefore(w.start) && it.time.isBefore(w.end) }
        if (pts.size < cfg.minHoursPerSource) return null
        val heat = pts.mapNotNull { p ->
            if (p.tempC != null && p.humidityPct != null) HeatIndex.celsius(p.tempC, p.humidityPct) else null
        }.maxOrNull()
        return SourceSummary(
            sourceId = src.sourceId,
            rainMax = if (!src.rainUsable) null else blockRainChance(pts) ?: pts.mapNotNull { it.rainChancePct }.maxOrNull(),
            tempMin = pts.mapNotNull { it.tempC }.minOrNull(),
            tempMax = pts.mapNotNull { it.tempC }.maxOrNull(),
            windMax = pts.mapNotNull { it.windKph }.maxOrNull(),
            gustMax = pts.mapNotNull { it.gustKph }.maxOrNull(),
            heatIndexMax = heat,
        )
    }

    /** Share of ensemble runs whose total rain over the block reaches [Config.blockRainMm]. */
    private fun blockRainChance(pts: List<HourlyPoint>): Int? {
        val runs = pts.mapNotNull { it.memberPrecipMm }
        if (runs.size < cfg.minHoursPerSource) return null
        val n = runs.minOf { it.size }
        if (n == 0) return null
        val wet = (0 until n).count { m -> runs.sumOf { it[m] } >= cfg.blockRainMm }
        return Math.round(100.0 * wet / n).toInt()
    }

    private fun confidenceOf(s: List<SourceSummary>): Confidence {
        if (s.size < 2) return Confidence.LOW
        val rainSpread = spread(s.mapNotNull { it.rainMax?.toDouble() })
        val tempSpread = spread(s.mapNotNull { it.tempMax })
        return when {
            rainSpread > cfg.lowAgreementRainSpread || tempSpread > cfg.lowAgreementTempSpread -> Confidence.LOW
            rainSpread <= cfg.highAgreementRainSpread && tempSpread <= cfg.highAgreementTempSpread -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
    }

    companion object {
        /** Lower bound of the wind range for each PAGASA Tropical Cyclone Wind Signal. */
        fun tcwsMinWindKph(level: Int): Int = when (level) {
            1 -> 39; 2 -> 62; 3 -> 89; 4 -> 118; else -> 185
        }

        fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        private fun spread(xs: List<Double>): Double =
            if (xs.size < 2) 0.0 else xs.max() - xs.min()
    }
}

/** Heat index ("feels like") from temperature and humidity — NOAA Rothfusz formula. */
object HeatIndex {
    fun celsius(tempC: Double, humidityPct: Int): Double {
        val t = tempC * 9 / 5 + 32
        val rh = humidityPct.toDouble()
        val simple = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094)
        val hiF = if ((simple + t) / 2 < 80) simple else {
            var hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh -
                0.00683783 * t * t - 0.05481717 * rh * rh + 0.00122874 * t * t * rh +
                0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
            if (rh < 13 && t in 80.0..112.0) hi -= ((13 - rh) / 4) * Math.sqrt((17 - Math.abs(t - 95)) / 17)
            if (rh > 85 && t in 80.0..87.0) hi += ((rh - 85) / 10) * ((87 - t) / 5)
            hi
        }
        return (hiF - 32) * 5 / 9
    }

    /** PAGASA heat index categories. */
    fun level(c: Int): HeatIndexLevel? = when {
        c >= 52 -> HeatIndexLevel.EXTREME_DANGER
        c >= 42 -> HeatIndexLevel.DANGER
        c >= 33 -> HeatIndexLevel.EXTREME_CAUTION
        c >= 27 -> HeatIndexLevel.CAUTION
        else -> null
    }
}
