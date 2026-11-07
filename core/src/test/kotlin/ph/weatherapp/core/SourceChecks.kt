package ph.weatherapp.core

import ph.weatherapp.core.engine.*
import ph.weatherapp.core.model.*
import ph.weatherapp.core.sources.*
import java.time.ZonedDateTime

// Parser checks using response shapes copied from each provider's documentation.

private var bad = 0
private fun expect(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) bad++ }

fun main() {
    val now = ZonedDateTime.of(2026, 11, 2, 5, 0, 0, 0, MANILA)

    // --- Open-Meteo: local times, one array per variable, nulls allowed ---
    val hours = (0 until 24).map { "\"2026-11-02T%02d:00\"".format(it) }
    fun series(f: (Int) -> String) = (0 until 24).joinToString(",") { f(it) }
    val om = """
      {"latitude":6.7,"longitude":121.97,"timezone":"Asia/Manila",
       "hourly":{"time":[${hours.joinToString(",")}],
         "temperature_2m":[${series { (25 + if (it in 11..14) 7 else 2).toString() }}],
         "relative_humidity_2m":[${series { "75" }}],
         "precipitation_probability":[${series { if (it == 3) "null" else if (it in 13..16) "65" else "20" }}],
         "wind_speed_10m":[${series { "11.5" }}],
         "wind_gusts_10m":[${series { "22.0" }}]}}
    """.trimIndent()
    val a = OpenMeteo.parse(om, now)
    expect("Open-Meteo: 24 hourly points", a.points.size == 24)
    expect("Open-Meteo: times are Manila local", a.points[6].time.hour == 6 && a.points[6].time.zone == MANILA)
    expect("Open-Meteo: null rain chance kept as empty", a.points[3].rainChancePct == null)
    expect("Open-Meteo: values read", a.points[14].rainChancePct == 65 && a.points[12].tempC == 32.0 && a.points[0].windKph == 11.5)

    // --- MET Norway: UTC times, m/s wind, probability sometimes missing ---
    val items = (0 until 24).map { i ->
        val utcDay = if (i < 2) 1 else 2           // 22:00Z Nov 1 = 6 AM Nov 2 Manila
        val utcHour = (22 + i) % 24
        val pop = when { i == 0 -> null; i in 6..9 -> "70.0"; else -> "15.0" }
        """{"time":"2026-11-%02dT%02d:00:00Z","data":{"instant":{"details":{"air_temperature":${if (i in 5..8) 31.4 else 27.0},"relative_humidity":78.2,"wind_speed":3.5,"wind_speed_of_gust":6.1}},"next_1_hours":{"summary":{"symbol_code":"rainshowers_day"},"details":{"precipitation_amount":0.4${if (pop != null) ",\"probability_of_precipitation\":$pop" else ""}}}}}"""
            .format(utcDay, utcHour)
    }
    val met = """{"type":"Feature","properties":{"meta":{"updated_at":"2026-11-01T21:00:00Z"},"timeseries":[${items.joinToString(",")}]}}"""
    val b = MetNorway.parse(met, now)
    expect("MET Norway: 24 points", b.points.size == 24)
    expect("MET Norway: 22:00Z → 6 AM Manila", b.points[0].time.hour == 6 && b.points[0].time.dayOfMonth == 2)
    expect("MET Norway: wind 3.5 m/s → 12.6 km/h", Math.abs((b.points[0].windKph ?: 0.0) - 12.6) < 0.01)
    expect("MET Norway: missing probability stays empty", b.points[0].rainChancePct == null && b.points[7].rainChancePct == 70)

    // --- Both sources through the engine ---
    val day = Windows.current(ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, MANILA))
    val f = Consolidator().consolidate(day, listOf(a, b), null)!!
    expect("engine uses both sources", f.sourcesUsed == listOf("open-meteo", "met-norway"))
    expect("Open-Meteo's rain chance not counted (it repeats GFS); MET's 70 used", f.rainChancePct == 70)
    println("   Day forecast: rain ${f.rainChancePct}%, ${f.tempMinC}–${f.tempMaxC}°C, wind ${f.windMaxKph} km/h, HI ${f.heatIndexMaxC}°C, ${f.confidence}")

    // --- Ensemble (ECMWF/GFS shape): control + members; rain chance = share of wet runs ---
    val ensHours = (0 until 12).map { "\"2026-11-02T%02d:00\"".format(6 + it) }
    fun runArr(wet: Boolean) = (0 until 12).joinToString(",") { if (wet && it in 6..8) "1.2" else "0.0" }
    // control + 9 members: 3 runs wet in hours 12–14 → 30%
    val runs = (0 until 10).joinToString(",") { m ->
        val key = if (m == 0) "precipitation" else "precipitation_member%02d".format(m)
        "\"$key\":[${runArr(m < 3)}]"
    }
    val ens = """{"hourly":{"time":[${ensHours.joinToString(",")}],$runs,
        "temperature_2m":[${(0 until 12).joinToString(",") { "29.5" }}],
        "temperature_2m_member01":[${(0 until 12).joinToString(",") { "40.0" }}],
        "relative_humidity_2m":[${(0 until 12).joinToString(",") { "80" }}],
        "wind_speed_10m":[${(0 until 12).joinToString(",") { "9.0" }}]}}"""
    val e = EcmwfEnsemble.parse(ens, now)
    expect("ensemble: 12 points from 6 AM", e.points.size == 12 && e.points[0].time.hour == 6)
    expect("ensemble: dry hour → 0%", e.points[0].rainChancePct == 0)
    expect("ensemble: 3 of 10 runs wet → 30%", e.points[7].rainChancePct == 30)
    expect("ensemble: each run's hourly rain kept", e.points[7].memberPrecipMm?.size == 10)

    // Block rain chance: a run must total ≥ 2 mm over the whole block
    val blk = Windows.current(ZonedDateTime.of(2026, 11, 2, 6, 0, 0, 0, MANILA))
    fun ensSource(perRun: (Int) -> List<Double>) = SourceForecast("ecmwf", now, (0 until 12).map { h ->
        HourlyPoint(ZonedDateTime.of(2026, 11, 2, 6 + h, 0, 0, 0, MANILA), null, 30.0, 9.0, null, 75,
            memberPrecipMm = (0 until 10).map { perRun(it)[h] }) })
    val drizzle = ensSource { (0 until 12).map { 0.1 } }              // every run drizzles all day: 1.2 mm
    val showers = ensSource { m -> (0 until 12).map { if (m < 4 && it == 7) 5.0 else 0.0 } }
    expect("drizzle in every run (1.2 mm total) → 0%, not 100%",
        Consolidator().consolidate(blk, listOf(drizzle), null)!!.rainChancePct == 0)
    expect("4 of 10 runs with a 5 mm shower → 40%",
        Consolidator().consolidate(blk, listOf(showers), null)!!.rainChancePct == 40)
    expect("ensemble: temperature from control run, not members", e.points[0].tempC == 29.5)
    expect("ensemble: missing gusts stay empty", e.points[0].gustKph == null && e.points[0].windKph == 9.0)
    expect("four sources registered", ALL_SOURCES.map { it.id } == listOf("ecmwf", "gfs", "open-meteo", "met-norway"))
    expect("ECMWF URL uses the ensemble API", EcmwfEnsemble.url(6.7, 121.98).contains("ensemble-api.open-meteo.com") &&
        EcmwfEnsemble.url(6.7, 121.98).contains("models=ecmwf_ifs025"))

    println(if (bad == 0) "\nAll source checks passed." else "\n$bad source check(s) failed.")
    if (bad > 0) kotlin.system.exitProcess(1)
}
