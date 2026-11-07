package ph.weatherapp.app

import android.content.Context
import ph.weatherapp.core.engine.Consolidator
import ph.weatherapp.core.log.AccuracyLog
import ph.weatherapp.core.log.LogEntry
import ph.weatherapp.core.model.ForecastWindow
import ph.weatherapp.core.model.SourceForecast
import ph.weatherapp.core.model.WindowKind
import ph.weatherapp.core.pagasa.PagasaFeed
import ph.weatherapp.core.pagasa.toAdvisory
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/** The accuracy log, kept as one JSON file on the phone. */
object AccuracyStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "accuracy-log.json")

    @Synchronized
    fun all(ctx: Context): List<LogEntry> = AccuracyLog.fromJson(file(ctx).takeIf { it.exists() }?.readText())

    @Synchronized
    private fun write(ctx: Context, entries: List<LogEntry>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AccuracyLog.toJson(entries))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    /** Saves today's daytime (6 AM–6 PM) forecast the first time one is made in the morning. */
    fun logMorning(ctx: Context, now: ZonedDateTime, place: Place, sources: List<SourceForecast>, feed: PagasaFeed) {
        runCatching {
            if (!AccuracyLog.isLoggingWindow(now)) return
            val entries = all(ctx)
            if (entries.any { it.date == now.toLocalDate() }) return
            val sixAm = now.toLocalDate().atTime(6, 0).atZone(now.zone)
            val day = ForecastWindow(WindowKind.DAY, sixAm, sixAm.plusHours(12))
            val f = Consolidator().consolidate(day, sources, feed.toAdvisory(day)) ?: return
            write(ctx, AccuracyLog.add(entries, AccuracyLog.entryFrom(f, place.name, now)))
        }
    }

    fun answer(ctx: Context, date: LocalDate, rained: Boolean) =
        write(ctx, AccuracyLog.answer(all(ctx), date, rained))
}
