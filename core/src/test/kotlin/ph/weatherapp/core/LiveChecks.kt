package ph.weatherapp.core

import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.model.*
import ph.weatherapp.core.pagasa.*
import java.time.ZonedDateTime

// Checks against real PAGASA responses saved on Nov 3, 2026 (src/test/resources/pagasa).

private var nok = 0
private fun t(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) nok++ }
private fun res(n: String) = object {}.javaClass.getResource("/pagasa/$n")!!.readText()
private fun mnl(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, MANILA)

fun main() {
    // --- Matching a city/municipality to PAGASA's forecast area
    fun m(name: String, prov: String?, region: String?) = PagasaLocations.match(name, prov, region)?.name
    t("Isabela City → its own area", m("Isabela City", null, "Zamboanga Peninsula") == "Isabela City")
    t("Lamitan City → Basilan", m("Lamitan City", "Basilan", "BARMM") == "Basilan")
    t("Zamboanga City → its own area", m("Zamboanga City", null, "Zamboanga Peninsula") == "Zamboanga City")
    t("Makati → Metro Manila", m("Makati City", null, "NCR") == "Metro Manila")
    t("Davao City → Metro Davao", m("Davao City", null, "Davao Region") == "Metro Davao")
    t("Kidapawan (Cotabato) → North Cotabato", m("Kidapawan City", "Cotabato", "SOCCSKSARGEN") == "North Cotabato")
    t("Ilagan City (Isabela province) → Isabela, not Isabela City", m("Ilagan City", "Isabela", "Cagayan Valley") == "Isabela")
    t("Baguio → Benguet", m("Baguio City", null, "CAR") == "Benguet")
    t("SGA town → no name match (nearest is used)", m("Kapalawan", null, "BARMM") == null)
    t("87 areas", PagasaLocations.ALL.size == 87)

    // --- Regional forecast data (Isabela City, entered 4:14 AM Nov 3)
    val now = mnl(3, 5, 20)
    val json = PagasaLive.regionalFeedJson(res("api-min-isabela.json"), Warnings(), now)
    t("regional converted", json != null)
    val feed = PagasaFeedJson.parse("isabela-city-basilan", json, null, now)
    val r = feed.regional
    t("issued for Nov 3, 5 AM", r?.issuedAt == mnl(3, 5))
    t("valid until Nov 4, 5 AM", r?.validUntil == mnl(4, 5))
    t("today's sky in PAGASA's words", r?.skyCondition == "Partly cloudy skies to at times cloudy with rainshowers or thunderstorm")
    t("today 24–33°C, tonight 24–31°C", r?.tempMinC == 24 && r.tempMaxC == 33 && r.tonightMinC == 24 && r.tonightMaxC == 31)
    t("winds with direction", r?.winds == "Light to Moderate (Northeast to East)")
    t("coastal", r?.coastalWaters == "Slight to Moderate")
    val adv = feed.toAdvisory(ForecastWindow(WindowKind.DAY, mnl(3, 6), mnl(3, 18)))
    t("advisory valid at 6 AM", adv.isValidAt(mnl(3, 6)))
    t("PAGASA everyday wording → rain possible, not committed", adv.forecastRainLikely == null)

    // NCR uses Manila local time with no zone ("2026-11-03 03:58:..")
    val ncr = PagasaFeedJson.parse("ncr", PagasaLive.regionalFeedJson(res("api-ncr-first.json"), Warnings(), now), null, now).regional
    t("NCR local timestamp read as Nov 3", ncr?.issuedAt == mnl(3, 5))

    // Before the 5 AM issue, yesterday's forecast is marked as such (valid only until 5 AM)
    val earlyFeed = PagasaFeedJson.parse("x", PagasaLive.regionalFeedJson(res("api-min-isabela.json"), Warnings(), mnl(3, 3)), null, mnl(3, 3))
    t("issue date follows created_at, not the clock", earlyFeed.regional?.issuedAt == mnl(3, 5))

    // --- Warnings tabs (thunderstorm advisory No. 15, issued 1:30 PM Nov 3, names CityOfIsabela)
    val html = res("minprsd-tabs.html")
    val at2 = mnl(3, 14)
    val w1 = PagasaLive.warnings(html, null, "City of Isabela", at2)
    t("thunderstorm advisory for Isabela City at 2 PM", w1.thunderstormAdvisory)
    t("no rainfall warning", w1.rainfallWarning == null)
    t("advisory valid ~3 hours", w1.validUntil == mnl(3, 16, 30))
    t("Lamitan listed too", PagasaLive.warnings(html, "Basilan", "City of Lamitan", at2).thunderstormAdvisory)
    t("Maluso (Basilan, second paragraph)", PagasaLive.warnings(html, "Basilan", "Maluso", at2).thunderstormAdvisory)
    t("Zamboanga City not listed", !PagasaLive.warnings(html, null, "City of Zamboanga", at2).thunderstormAdvisory)
    t("Davao City not listed", !PagasaLive.warnings(html, null, "City of Davao", at2).thunderstormAdvisory)
    t("expired by 6 PM", !PagasaLive.warnings(html, null, "City of Isabela", mnl(3, 18)).thunderstormAdvisory)
    t("Isabela province town not confused with Isabela City",
        !PagasaLive.warnings(html, "Isabela", "City of Ilagan", at2).thunderstormAdvisory)

    // --- iHeatMAP (shape as used by the CDRRMO heat advisory kit)
    val pixel = """{"values":[
      {"date":"2026-11-02","time":"12:00","value":33},
      {"date":"2026-11-03","time":"08:00","value":29},{"date":"2026-11-03","time":"11:00","value":33.4},
      {"date":"2026-11-03","time":"14:00","value":31},{"date":"2026-11-04","time":"11:00","value":34}]}"""
    val heatJson = PagasaLive.heatFeedJson(PagasaLive.issuanceDate("""{"latest_issuance_date":"2026-11-03"}"""), pixel, "Isabela City", mnl(3, 5, 20))
    val heat = PagasaFeedJson.parse("x", null, heatJson, mnl(3, 5, 20)).heatIndex
    t("heat: only today's hours", heat?.hourlyC == mapOf(8 to 29, 11 to 33, 14 to 31))
    t("heat: peak 33 at 11", heat?.peakC == 33 && heat.peakHour == 11)
    t("heat: compact date format", PagasaLive.parseDay("20261103") == mnl(3, 0).toLocalDate())
    t("heat: nothing for today → null", PagasaLive.heatFeedJson("2026-11-02", """{"values":[{"date":"2026-11-02","time":"12:00","value":33}]}""", "x", mnl(3, 5)) == null)

    // --- Heat card: PAGASA first, models otherwise; today until 6 PM, then tomorrow
    val hi = HeatIndexForecast(mnl(5, 5), "x", mnl(5, 0).toLocalDate(), mapOf(8 to 29, 11 to 34, 13 to 35, 14 to 33, 16 to 31))
    val day = ph.weatherapp.core.engine.HeatDays.forecast(mnl(5, 7), hi, emptyList())
    t("heat card: PAGASA peak 35 at 1 PM", day?.peakC == 35 && day.peakHour == 13 && day.fromPagasa)
    t("heat card: extreme caution 11 AM–2 PM", day?.level == HeatIndexLevel.EXTREME_CAUTION && day.topFrom == 11 && day.topTo == 14)
    val pts = (0..47).map { h -> HourlyPoint(mnl(5, 0).plusHours(h.toLong()), 10, 32.0, 5.0, 8.0, 70) }
    val evening = ph.weatherapp.core.engine.HeatDays.forecast(mnl(5, 19), hi, listOf(SourceForecast("m", mnl(5, 0), pts)))
    t("heat card: after 6 PM shows tomorrow from models", evening?.date == mnl(6, 0).toLocalDate() && !evening.fromPagasa && evening.peakC >= 33)

    println(if (nok == 0) "All live checks passed" else "$nok live check(s) FAILED")
    if (nok > 0) kotlin.system.exitProcess(1)
}
