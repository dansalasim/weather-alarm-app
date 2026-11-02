package ph.weatherapp.core.engine

import ph.weatherapp.core.model.ForecastWindow
import ph.weatherapp.core.model.WindowKind
import java.time.ZoneId
import java.time.ZonedDateTime

val MANILA: ZoneId = ZoneId.of("Asia/Manila")

/**
 * Splits time into 12-hour windows: DAY 6 AM–6 PM and NIGHT 6 PM–6 AM.
 */
object Windows {

    /** The window that contains [now]. */
    fun current(now: ZonedDateTime): ForecastWindow {
        val t = now.withZoneSameInstant(MANILA)
        val sixAm = t.toLocalDate().atTime(6, 0).atZone(MANILA)
        val sixPm = t.toLocalDate().atTime(18, 0).atZone(MANILA)
        return when {
            t.isBefore(sixAm) -> ForecastWindow(WindowKind.NIGHT, sixPm.minusDays(1), sixAm)
            t.isBefore(sixPm) -> ForecastWindow(WindowKind.DAY, sixAm, sixPm)
            else -> ForecastWindow(WindowKind.NIGHT, sixPm, sixAm.plusDays(1))
        }
    }

    fun next(w: ForecastWindow): ForecastWindow = ForecastWindow(
        kind = if (w.kind == WindowKind.DAY) WindowKind.NIGHT else WindowKind.DAY,
        start = w.end,
        end = w.end.plusHours(12),
    )

    /**
     * The two windows the alarm card shows: the main one and a preview.
     * If the alarm rings within the last 2 hours of a window (e.g. 5 AM, near the
     * end of the night), the main card jumps to the upcoming window instead,
     * because that's what the user is about to live through.
     */
    fun forAlarm(alarmTime: ZonedDateTime): Pair<ForecastWindow, ForecastWindow> {
        val cur = current(alarmTime)
        val hoursLeft = java.time.Duration.between(alarmTime, cur.end).toMinutes() / 60.0
        val main = if (hoursLeft <= 2.0) next(cur) else cur
        return main to next(main)
    }
}
