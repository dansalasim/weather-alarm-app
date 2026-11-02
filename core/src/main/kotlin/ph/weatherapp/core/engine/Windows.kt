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

    // ---------- Premium: 6-hour blocks (12 MN–6 AM, 6 AM–12 NN, 12 NN–6 PM, 6 PM–12 MN) ----------

    /** The 6-hour block that contains [now]. Morning/afternoon count as DAY, evening/overnight as NIGHT. */
    fun block6(now: ZonedDateTime): ForecastWindow {
        val t = now.withZoneSameInstant(MANILA)
        val h = t.hour / 6 * 6
        val start = t.toLocalDate().atTime(h, 0).atZone(MANILA)
        return ForecastWindow(kindOf6(h), start, start.plusHours(6), sixHour = true)
    }

    fun nextBlock6(w: ForecastWindow): ForecastWindow =
        ForecastWindow(kindOf6(w.end.hour), w.end, w.end.plusHours(6), sixHour = true)

    /** [count] consecutive 6-hour blocks starting with the one containing [now] (4 = the next 24 hours). */
    fun blocks6(now: ZonedDateTime, count: Int = 4): List<ForecastWindow> =
        generateSequence(block6(now)) { nextBlock6(it) }.take(count).toList()

    /** For an alarm: skip the current block if less than an hour of it is left. */
    fun blocks6ForAlarm(alarmTime: ZonedDateTime, count: Int = 4): List<ForecastWindow> {
        val cur = block6(alarmTime)
        val minutesLeft = java.time.Duration.between(alarmTime, cur.end).toMinutes()
        val first = if (minutesLeft <= 60) nextBlock6(cur) else cur
        return generateSequence(first) { nextBlock6(it) }.take(count).toList()
    }

    private fun kindOf6(startHour: Int) = if (startHour == 6 || startHour == 12) WindowKind.DAY else WindowKind.NIGHT
}

/** Names for windows, on screen and spoken. Works for 12-hour windows and 6-hour blocks. */
object Periods {

    private fun is6(w: ForecastWindow) = w.sixHour

    /** Short screen label, e.g. "Morning · 6 AM–12 PM", "Tomorrow · 6 AM–6 PM", "Tonight · until 6 AM". */
    fun label(w: ForecastWindow, lang: Lang, partial: Boolean, today: java.time.LocalDate): String {
        val en = lang == Lang.EN
        val startDay = w.start.toLocalDate()
        val endText = when (w.end.hour) { 0 -> if (en) "12 AM" else "12 AM"; 6 -> "6 AM"; 12 -> "12 PM"; else -> "6 PM" }
        if (is6(w)) {
            val h = w.nominalStartHour
            val base = when (h) {
                6 -> if (en) "Morning" else "Umaga"
                12 -> if (en) "Afternoon" else "Hapon"
                18 -> if (en) "Evening" else "Gabi"
                else -> if (en) "Overnight" else "Madaling-araw"
            }
            val tomorrow = h != 0 && startDay.isAfter(today)
            val name = if (!tomorrow) base else if (en) "Tomorrow ${base.lowercase()}" else "Bukas ng ${base.lowercase()}"
            val range = when (h) { 6 -> "6 AM–12 PM"; 12 -> "12–6 PM"; 18 -> "6 PM–12 AM"; else -> "12–6 AM" }
            return name + " · " + if (partial) (if (en) "until $endText" else "hanggang $endText") else range
        }
        return if (w.kind == WindowKind.DAY) {
            val day = if (!startDay.isAfter(today)) (if (en) "Today" else "Ngayon") else (if (en) "Tomorrow" else "Bukas")
            day + if (partial) (if (en) " · until 6 PM" else " · hanggang 6 PM") else " · 6 AM–6 PM"
        } else {
            // A night that started yesterday evening is still "tonight" after midnight.
            val night = if (!startDay.isAfter(today)) (if (en) "Tonight" else "Ngayong gabi") else (if (en) "Tomorrow night" else "Bukas ng gabi")
            night + if (partial) (if (en) " · until 6 AM" else " · hanggang 6 AM") else " · 6 PM–6 AM"
        }
    }

    /** Spoken name, e.g. "this morning, 6 AM to 12 noon" / "ngayong umaga, alas-sais hanggang tanghali". */
    fun spoken(w: ForecastWindow, lang: Lang): String {
        val en = lang == Lang.EN
        if (!is6(w)) return when (w.kind) {
            WindowKind.DAY -> if (en) "today, 6 AM to 6 PM" else "ngayong araw, alas-sais ng umaga hanggang alas-sais ng gabi"
            WindowKind.NIGHT -> if (en) "tonight, 6 PM to 6 AM" else "ngayong gabi, alas-sais ng gabi hanggang alas-sais ng umaga"
        }
        return when (w.nominalStartHour) {
            6 -> if (en) "this morning, 6 AM to 12 noon" else "ngayong umaga, alas-sais hanggang tanghali"
            12 -> if (en) "this afternoon, 12 noon to 6 PM" else "ngayong hapon, tanghali hanggang alas-sais ng gabi"
            18 -> if (en) "this evening, 6 PM to midnight" else "ngayong gabi, alas-sais hanggang hatinggabi"
            else -> if (en) "overnight, midnight to 6 AM" else "madaling-araw, hatinggabi hanggang alas-sais ng umaga"
        }
    }
}
