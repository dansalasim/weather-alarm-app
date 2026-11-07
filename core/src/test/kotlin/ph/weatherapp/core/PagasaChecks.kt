package ph.weatherapp.core

import ph.weatherapp.core.engine.*
import ph.weatherapp.core.model.*
import ph.weatherapp.core.pagasa.*
import java.time.LocalDate
import java.time.ZonedDateTime

// Test cases taken from CDRRMO Isabela City's own infographics (Oct 22, 28, 30, 2026).

private var fails = 0
private fun ok(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) fails++ }
private fun at(m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, MANILA)

fun main() {
    // --- PAGASA wording → rain outlook ---
    ok("Oct 28 'partly cloudy … with rainshowers or thunderstorms' → POSSIBLE (everyday wording)",
        PagasaText.rainOutlook("Partly cloudy skies to at times cloudy with rainshowers or thunderstorms") == RainOutlook.POSSIBLE)
    ok("'partly cloudy … with scattered rainshowers' → LIKELY",
        PagasaText.rainOutlook("Partly cloudy to cloudy skies with scattered rainshowers and thunderstorms") == RainOutlook.LIKELY)
    ok("Oct 30 'cloudy skies with rainshowers and thunderstorms' → LIKELY",
        PagasaText.rainOutlook("Cloudy skies with rainshowers and thunderstorms") == RainOutlook.LIKELY)
    ok("'isolated rainshowers or thunderstorms' → POSSIBLE",
        PagasaText.rainOutlook("Partly cloudy to cloudy skies with isolated rainshowers or thunderstorms") == RainOutlook.POSSIBLE)
    ok("'localized thunderstorms' → POSSIBLE",
        PagasaText.rainOutlook("Partly cloudy to cloudy skies with localized thunderstorms") == RainOutlook.POSSIBLE)
    ok("'moderate to heavy rains' → VERY_LIKELY",
        PagasaText.rainOutlook("Cloudy skies with moderate to heavy rains") == RainOutlook.VERY_LIKELY)
    ok("no rain words → UNLIKELY", PagasaText.rainOutlook("Partly cloudy to cloudy skies") == RainOutlook.UNLIKELY)

    // --- Oct 28: full feed, both infographics ---
    val oct28 = PagasaFeed(
        areaId = "isabela-city-basilan",
        regional = RegionalForecast(at(10, 28, 5), at(10, 29, 5),
            "Partly cloudy skies to at times cloudy with rainshowers or thunderstorms",
            26, 34, "Light to Moderate, from Northeast to East", "Slight to Moderate (0.6-2.1 m)", null),
        heatIndex = HeatIndexForecast(at(10, 28, 5, 52), "Brgy. Sunrise Village, City of Isabela", LocalDate.of(2026, 10, 28),
            mapOf(8 to 31, 9 to 33, 10 to 34, 11 to 34, 12 to 34, 13 to 33, 14 to 33, 15 to 33, 16 to 32, 17 to 31)),
        warnings = Warnings(),
        updatedAt = at(10, 28, 6, 35),
    )
    ok("Oct 28 heat index peak = 34°C", oct28.heatIndex?.peakC == 34)
    ok("Oct 28 peak hour = 10 AM (first of the 34°C hours)", oct28.heatIndex?.peakHour == 10)
    val adv = oct28.toAdvisory()
    ok("Oct 28 → PAGASA says rain possible (not committing)", adv.forecastRainLikely == null)
    ok("Oct 28 → heat index 34 → Extreme Caution", HeatIndex.level(adv.heatIndexForecastC!!) == HeatIndexLevel.EXTREME_CAUTION)

    // Gridded sources that disagree badly: PAGASA should settle it
    val day = ForecastWindow(WindowKind.DAY, at(10, 28, 6), at(10, 28, 18))
    fun src(id: String, rain: Int) = SourceForecast(id, at(10, 28, 4),
        (0 until 12).map { HourlyPoint(at(10, 28, 6).plusHours(it.toLong()), rain, 31.0, 12.0, 18.0, 70) })
    val f = Consolidator().consolidate(day, listOf(src("open-meteo", 15), src("met-no", 70), src("owm", 35)), adv)!!
    ok("Oct 28 consolidated: 'possible' doesn't push rain up (median 35%)", f.rainChancePct == 35)
    val wetModels = Consolidator().consolidate(day, listOf(src("a", 95), src("b", 30), src("c", 90)), adv)!!
    ok("Oct 28: models at 90% but PAGASA only 'possible' → capped at 60%", wetModels.rainChancePct == 60)
    ok("Oct 28 consolidated: heat index uses iHeatMAP 34°C", f.heatIndexMaxC == 34)
    val recs = Recommender.recommend(f).map { it.code }
    ok("Oct 28 advice includes heat, no umbrella at 35%", recs.contains("HEAT_HIGH") && recs.none { it.startsWith("UMBRELLA") })
    println("   Oct 28 advice: $recs  |  notes: ${f.pagasaOverrides}")

    // --- Oct 22: heat only; peak 33 at 11 AM ---
    val oct22 = HeatIndexForecast(at(10, 22, 5, 52), "Brgy. Sunrise Village, City of Isabela", LocalDate.of(2026, 10, 22),
        mapOf(8 to 31, 9 to 33, 10 to 32, 11 to 33, 12 to 33, 13 to 31, 14 to 31, 15 to 32, 16 to 32, 17 to 31))
    ok("Oct 22 peak = 33°C, Extreme Caution", oct22.peakC == 33 && HeatIndex.level(33) == HeatIndexLevel.EXTREME_CAUTION)

    // --- Warnings win over a calm forecast ---
    val stormy = oct28.copy(warnings = Warnings(rainfallWarning = RainfallWarning.ORANGE, validUntil = at(10, 28, 20)))
    val g = Consolidator().consolidate(day, listOf(src("a", 20), src("b", 25)), stormy.toAdvisory())!!
    ok("Orange warning in feed → rain ≥ 85% and FLOOD advice first",
        g.rainChancePct >= 85 && Recommender.recommend(g).first().code == "FLOOD")

    println(if (fails == 0) "\nAll PAGASA checks passed." else "\n$fails PAGASA check(s) failed.")
    if (fails > 0) kotlin.system.exitProcess(1)
}
