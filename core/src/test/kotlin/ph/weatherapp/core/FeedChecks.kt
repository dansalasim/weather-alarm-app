package ph.weatherapp.core

import ph.weatherapp.core.engine.*
import ph.weatherapp.core.model.*
import ph.weatherapp.core.pagasa.*
import java.time.ZonedDateTime

// Checks using the real feed files the CDRRMO morning tasks saved on Nov 2, 2026.

private var nok = 0
private fun t(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) nok++ }
private fun mnl(d: Int, h: Int) = ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, MANILA)

private const val REGIONAL_NOV2 = """{
 "part": "regional", "areaId": "isabela-city-basilan",
 "source": "DOST-PAGASA Mindanao PRSD Regional Forecast",
 "issuedAt": "2026-11-02T05:00:00+08:00", "validUntil": "2026-11-03T05:00:00+08:00",
 "latestAvailable": false,
 "skyCondition": "Partly cloudy skies to at times cloudy with rainshowers or thunderstorm",
 "tempMinC": 25, "tempMaxC": 34,
 "tonightSkyCondition": "Partly cloudy skies to at times cloudy with rainshowers or thunderstorm",
 "tonightMinC": 25, "tonightMaxC": 33,
 "winds": "Light to Moderate (Northeast to East)",
 "coastalWaters": "Slight to Moderate (0.6 to 2.5 meters)",
 "synopsis": null,
 "warnings": {"tcwsLevel": null, "rainfallWarning": null, "thunderstormAdvisory": false, "validUntil": null, "text": null}
}"""

// Real file: PAGASA hadn't issued Nov 2 yet at 5:50 AM, so it carries Nov 1 data ("stale").
private const val HEAT_NOV2_STALE = """{"part": "heatIndex", "areaId": "isabela-city-basilan", "source": "DOST-PAGASA iHeatMAP (ECMWF-based)", "issuedAt": "2026-11-02T05:52:00+08:00", "issuanceDate": "2026-11-01", "stale": true, "location": "Brgy. Sunrise Village, City of Isabela", "date": "2026-11-01", "hourlyC": {"8": 28, "9": 30, "10": 31, "11": 32, "12": 32, "13": 29, "14": 28, "15": 30, "16": 29, "17": 28}, "peakC": 32, "peakHour": 11}"""

fun main() {
    val now = mnl(2, 12)
    val feed = PagasaFeedJson.parse("isabela-city-basilan", REGIONAL_NOV2, HEAT_NOV2_STALE, now)
    t("regional forecast read", feed.regional?.tempMaxC == 34 && feed.regional?.tonightMinC == 25)
    t("no warnings", feed.warnings.tcwsLevel == null && feed.warnings.rainfallWarning == null && !feed.warnings.thunderstormAdvisory)
    t("heat feed read (10 hours)", feed.heatIndex?.hourlyC?.size == 10)

    val today = ForecastWindow(WindowKind.DAY, mnl(2, 6), mnl(2, 18))
    val adv = feed.toAdvisory(today)
    t("stale heat index (Nov 1) is NOT used for Nov 2", adv.heatIndexForecastC == null)
    t("'rainshowers or thunderstorm' → rain likely", adv.forecastRainLikely == true)
    t("PAGASA wording kept for the card", adv.skyCondition?.startsWith("Partly cloudy") == true)

    // Same-day heat file: only the hours left in the window count
    val fresh = HEAT_NOV2_STALE.replace("\"date\": \"2026-11-01\"", "\"date\": \"2026-11-02\"")
    val feed2 = PagasaFeedJson.parse("isabela-city-basilan", REGIONAL_NOV2, fresh, now)
    t("fresh heat: whole day peak = 32", feed2.toAdvisory(today).heatIndexForecastC == 32)
    val restOfDay = ForecastWindow(WindowKind.DAY, mnl(2, 14), mnl(2, 18))
    t("fresh heat: from 2 PM peak = 30", feed2.toAdvisory(restOfDay).heatIndexForecastC == 30)
    t("no heat index for the night", feed2.toAdvisory(ForecastWindow(WindowKind.NIGHT, mnl(2, 18), mnl(3, 6))).heatIndexForecastC == null)

    // Validity: tomorrow's day window is outside the 5 AM → 5 AM forecast, so the engine ignores it
    val tomorrow = ForecastWindow(WindowKind.DAY, mnl(3, 6), mnl(3, 18))
    val src = SourceForecast("x", now, (0 until 12).map { HourlyPoint(mnl(3, 6).plusHours(it.toLong()), 20, 30.0, 10.0, 15.0, 70) })
    t("yesterday's forecast not applied to tomorrow", Consolidator().consolidate(tomorrow, listOf(src), feed.toAdvisory(tomorrow))!!.pagasa == null)

    // Warnings, missing files, junk
    val warned = REGIONAL_NOV2.replace("\"rainfallWarning\": null", "\"rainfallWarning\": \"orange\"").replace("\"tcwsLevel\": null", "\"tcwsLevel\": 2")
    val wf = PagasaFeedJson.parse("isabela-city-basilan", warned, null, now)
    t("orange warning + Signal No. 2 read", wf.warnings.rainfallWarning == RainfallWarning.ORANGE && wf.warnings.tcwsLevel == 2)
    t("missing heat file is fine", wf.heatIndex == null && wf.regional != null)
    val junk = PagasaFeedJson.parse("isabela-city-basilan", "<html>404</html>", "not json", now)
    t("junk files → empty feed, no crash", junk.regional == null && junk.heatIndex == null)

    println(if (nok == 0) "\nAll feed checks passed." else "\n$nok feed check(s) failed.")
    if (nok > 0) kotlin.system.exitProcess(1)
}
