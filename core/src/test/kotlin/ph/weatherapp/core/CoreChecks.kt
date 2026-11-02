package ph.weatherapp.core

import ph.weatherapp.core.engine.*
import ph.weatherapp.core.model.*
import ph.weatherapp.core.premium.Entitlements
import java.time.ZonedDateTime

// Plain checks you can run without JUnit. In Android Studio, move these into JUnit tests.

private var failures = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failures++
}

private fun t(day: Int, hour: Int): ZonedDateTime =
    ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, MANILA)

/** A source with the same values every hour across 24 hours from 6 PM on Nov 2. */
private fun source(id: String, rain: Int, temp: Double, wind: Double, hum: Int = 75) = SourceForecast(
    id, t(2, 17),
    (0 until 36).map { h ->
        val time = t(2, 18).plusHours(h.toLong())
        HourlyPoint(time, rain, temp + if (time.hour in 11..14) 3 else 0, wind, wind * 1.5, hum)
    },
)

fun main() {
    // --- Windows ---
    val w5am = Windows.current(t(3, 5))
    check("5 AM is in the NIGHT window", w5am.kind == WindowKind.NIGHT && w5am.start == t(2, 18) && w5am.end == t(3, 6))
    check("noon is in the DAY window", Windows.current(t(3, 12)).kind == WindowKind.DAY)
    check("6 PM starts NIGHT", Windows.current(t(3, 18)).let { it.kind == WindowKind.NIGHT && it.start == t(3, 18) })
    val (main5, prev5) = Windows.forAlarm(t(3, 5))
    check("5 AM alarm shows today's DAY first, then tonight", main5.kind == WindowKind.DAY && main5.start == t(3, 6) && prev5.kind == WindowKind.NIGHT)
    val (main21, prev21) = Windows.forAlarm(t(2, 21))
    check("9 PM alarm shows tonight, then tomorrow's DAY", main21.kind == WindowKind.NIGHT && prev21.kind == WindowKind.DAY)

    val day = ForecastWindow(WindowKind.DAY, t(3, 6), t(3, 18))
    val c = Consolidator()

    // --- Agreement ---
    val agree = c.consolidate(day, listOf(source("open-meteo", 60, 31.0, 12.0), source("met-no", 65, 30.0, 14.0), source("owm", 55, 31.0, 10.0)), null)!!
    check("median rain chance = 60", agree.rainChancePct == 60)
    check("sources agree → HIGH confidence", agree.confidence == Confidence.HIGH)
    check("temp range 31–34", agree.tempMinC == 31 && agree.tempMaxC == 34)
    check("heat index computed for daytime", (agree.heatIndexMaxC ?: 0) >= 40)

    // --- Disagreement → PAGASA prevails ---
    val messy = listOf(source("open-meteo", 10, 30.0, 8.0), source("met-no", 80, 30.0, 8.0), source("owm", 40, 30.0, 8.0))
    val noPagasa = c.consolidate(day, messy, null)!!
    check("sources disagree → LOW confidence", noPagasa.confidence == Confidence.LOW)
    val pagasaRain = PagasaAdvisory(t(3, 5), t(3, 20), forecastRainLikely = true)
    val withPagasa = c.consolidate(day, messy, pagasaRain)!!
    check("PAGASA 'rain likely' lifts 40% → 60%", withPagasa.rainChancePct == 60 && withPagasa.pagasaOverrides.isNotEmpty())
    val pagasaDry = PagasaAdvisory(t(3, 5), t(3, 20), forecastRainLikely = false)
    check("PAGASA 'rain unlikely' caps at 30%", c.consolidate(day, messy, pagasaDry)!!.rainChancePct == 30)
    check("PAGASA text forecast does NOT override when sources agree",
        c.consolidate(day, listOf(source("a", 80, 30.0, 8.0), source("b", 75, 30.0, 8.0)), pagasaDry)!!.rainChancePct == 78)

    // --- Warnings always win ---
    val signal2 = PagasaAdvisory(t(3, 0), t(3, 23), tcwsLevel = 2)
    val storm = c.consolidate(day, listOf(source("a", 30, 29.0, 20.0), source("b", 35, 29.0, 22.0)), signal2)!!
    check("Signal No. 2 → rain ≥ 80 and wind ≥ 62", storm.rainChancePct >= 80 && storm.windMaxKph >= 62)
    check("Signal → URGENT recommendation first", Recommender.recommend(storm).first().code == "TCWS")
    val orange = PagasaAdvisory(t(3, 0), t(3, 23), rainfallWarning = RainfallWarning.ORANGE)
    check("Orange rainfall warning → rain ≥ 85", c.consolidate(day, messy, orange)!!.rainChancePct >= 85)
    val expired = PagasaAdvisory(t(1, 0), t(1, 12), tcwsLevel = 3)
    check("expired PAGASA advisory is ignored", c.consolidate(day, messy, expired)!!.pagasa == null)

    // --- Recommendations ---
    val recs = Recommender.recommend(agree)
    check("60% rain → small umbrella advice", recs.any { it.code == "UMBRELLA_MAYBE" })
    check("hot day → drink water advice", recs.any { it.code.startsWith("HEAT") })
    val fairNight = c.consolidate(ForecastWindow(WindowKind.NIGHT, t(2, 18), t(3, 6)), listOf(source("a", 5, 25.0, 5.0)), null)!!
    check("clear night → FAIR", Recommender.recommend(fairNight).map { it.code } == listOf("FAIR"))
    check("no heat index at night", fairNight.heatIndexMaxC == null)
    check("only one source → LOW confidence", fairNight.confidence == Confidence.LOW)

    // --- Voice ---
    val en = VoiceScript.build(agree, fairNight, recs, Lang.EN)
    val fil = VoiceScript.build(agree, fairNight, recs, Lang.FIL)
    println("\nVoice (EN): $en\nVoice (FIL): $fil\n")
    check("voice script mentions rain chance", en.contains("60 percent") && fil.contains("60 porsyento"))

    // --- Premium ---
    check("annual saves ₱239", Entitlements.annualSavingsPhp() == 239)

    println(if (failures == 0) "\nAll checks passed." else "\n$failures check(s) failed.")
    if (failures > 0) kotlin.system.exitProcess(1)
}
