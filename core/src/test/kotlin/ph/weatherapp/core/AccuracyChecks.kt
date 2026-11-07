package ph.weatherapp.core

import ph.weatherapp.core.engine.*
import ph.weatherapp.core.log.*
import ph.weatherapp.core.model.*
import java.time.LocalDate
import java.time.ZonedDateTime

private var bad = 0
private fun t(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) bad++ }
private fun at(d: Int, h: Int) = ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, MANILA)

fun main() {
    val possible = "Partly cloudy skies to at times cloudy with rainshowers or thunderstorm"
    val likely = "Cloudy skies with rainshowers and thunderstorm"

    // Consolidator now reports each source's own rain chance
    val day = ForecastWindow(WindowKind.DAY, at(8, 6), at(8, 18))
    fun src(id: String, rain: Int) = SourceForecast(id, at(8, 5),
        (0 until 12).map { HourlyPoint(at(8, 6).plusHours(it.toLong()), rain, 30.0, 10.0, null, 75) })
    val f = Consolidator().consolidate(day, listOf(src("ecmwf", 20), src("gfs", 80)), null)!!
    t("per-source rain kept", f.sourceRain == mapOf("ecmwf" to 20, "gfs" to 80))

    // Logging only in the morning, once per day
    t("logs at 5 AM", AccuracyLog.isLoggingWindow(at(8, 5)))
    t("doesn't log at 3 PM", !AccuracyLog.isLoggingWindow(at(8, 15)))
    val e1 = AccuracyLog.entryFrom(f, "City of Isabela", at(8, 5))
    var log = AccuracyLog.add(emptyList(), e1)
    log = AccuracyLog.add(log, e1.copy(appRainPct = 99))
    t("one entry per day, first one kept", log.size == 1 && log[0].appRainPct == 50)

    // When to ask
    t("not asked before 5 PM", AccuracyLog.toAsk(log, at(8, 16)) == null)
    t("asked at 5 PM", AccuracyLog.toAsk(log, at(8, 17))?.date == LocalDate.of(2026, 11, 8))
    t("still asked next morning", AccuracyLog.toAsk(log, at(9, 7))?.date == LocalDate.of(2026, 11, 8))
    t("not asked after 3 days", AccuracyLog.toAsk(log, at(11, 18)) == null)
    log = AccuracyLog.answer(log, LocalDate.of(2026, 11, 8), false)
    t("not asked once answered", AccuracyLog.toAsk(log, at(8, 18)) == null)

    // Scoring: 3 dry days with "possible" wording, 1 rainy day with "likely" wording
    fun entry(d: Int, app: Int, ec: Int, gfs: Int, sky: String, rained: Boolean) = LogEntry(
        LocalDate.of(2026, 11, d), "City of Isabela", at(d, 5), app, mapOf("ecmwf" to ec, "gfs" to gfs), sky, rained)
    val days = listOf(
        entry(10, 73, 40, 90, possible, false),
        entry(11, 30, 20, 70, possible, false),
        entry(12, 20, 10, 60, possible, false),
        entry(13, 80, 85, 95, likely, true),
        entry(14, 60, 50, 50, possible, true),
    )
    val sc = AccuracyLog.scores(days).associateBy { it.name }
    t("app: 4/5, 1 false alarm", sc["Weather Alarm PH"]?.let { it.correct == 4 && it.falseAlarms == 1 && it.misses == 0 } == true)
    t("ECMWF: 5/5", sc["ECMWF"]?.correct == 5)
    t("GFS: 2/5, 3 false alarms", sc["GFS"]?.let { it.correct == 2 && it.falseAlarms == 3 } == true)
    t("PAGASA (new reading): 4/5, 1 missed", sc["PAGASA"]?.let { it.correct == 4 && it.misses == 1 } == true)
    t("PAGASA (old reading): 2/5, 3 false alarms", sc["PAGASA (old reading)"]?.let { it.correct == 2 && it.falseAlarms == 3 } == true)
    t("unanswered days not scored", AccuracyLog.scores(days + entry(15, 90, 90, 90, likely, false).copy(rained = null))
        .first().days == 5)

    // Storage round trip, and junk is ignored
    val back = AccuracyLog.fromJson(AccuracyLog.toJson(days))
    t("JSON round trip", back == days)
    t("junk file → empty log", AccuracyLog.fromJson("not json").isEmpty())

    println(if (bad == 0) "\nAll accuracy checks passed." else "\n$bad accuracy check(s) failed.")
    if (bad > 0) kotlin.system.exitProcess(1)
}
