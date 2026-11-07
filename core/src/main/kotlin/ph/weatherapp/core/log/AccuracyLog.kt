package ph.weatherapp.core.log

import kotlinx.serialization.json.*
import ph.weatherapp.core.model.WindowForecast
import ph.weatherapp.core.pagasa.PagasaText
import ph.weatherapp.core.pagasa.RainOutlook
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * One day's MORNING forecast for the daytime (6 AM–6 PM), and later the user's answer
 * to "Did it rain?". Kept on the phone; used to score each source against what happened.
 */
data class LogEntry(
    val date: LocalDate,
    val placeName: String,
    val recordedAt: ZonedDateTime,
    val appRainPct: Int,                    // what the app showed
    val sourceRainPct: Map<String, Int>,    // each source's own chance (ecmwf, gfs, ...)
    val pagasaSky: String?,                 // PAGASA's exact words (same text the CDRRMO forecast uses)
    val rained: Boolean? = null,            // the user's answer; null = not answered yet
)

/** How one predictor did over the answered days. */
data class Score(
    val name: String,
    val days: Int,
    val correct: Int,
    val falseAlarms: Int,   // said rain, stayed dry
    val misses: Int,        // said dry, it rained
) {
    val pct: Int get() = if (days == 0) 0 else Math.round(100.0 * correct / days).toInt()
}

object AccuracyLog {
    /** A chance at or above this counts as "the forecast said rain". */
    const val RAIN_CALL_PCT = 50
    const val MAX_DAYS = 120

    /** Only the first forecast made between 4 AM and noon is logged: that's what the alarm shows. */
    fun isLoggingWindow(now: ZonedDateTime) = now.hour in 4..11

    fun entryFrom(day: WindowForecast, placeName: String, now: ZonedDateTime) = LogEntry(
        date = day.window.start.toLocalDate(),
        placeName = placeName,
        recordedAt = now,
        appRainPct = day.rainChancePct,
        sourceRainPct = day.sourceRain,
        pagasaSky = day.pagasa?.skyCondition,
    )

    /** Adds [e] unless that date is already logged. Keeps the newest [MAX_DAYS]. */
    fun add(entries: List<LogEntry>, e: LogEntry): List<LogEntry> =
        if (entries.any { it.date == e.date }) entries
        else (entries + e).sortedBy { it.date }.takeLast(MAX_DAYS)

    fun answer(entries: List<LogEntry>, date: LocalDate, rained: Boolean) =
        entries.map { if (it.date == date) it.copy(rained = rained) else it }

    /** The entry to ask about: today's after 5 PM, else the latest unanswered from the last 2 days. */
    fun toAsk(entries: List<LogEntry>, now: ZonedDateTime): LogEntry? {
        val today = now.toLocalDate()
        return entries.filter { it.rained == null }
            .filter { (it.date == today && now.hour >= 17) || (it.date.isBefore(today) && !it.date.isBefore(today.minusDays(2))) }
            .maxByOrNull { it.date }
    }

    /**
     * Scores the app, each model, and PAGASA's wording. PAGASA counts as "said rain" for
     * "likely" wording (cloudy skies with…, scattered, widespread, heavy) — the reading the app and
     * the CDRRMO forecast now use. "PAGASA (old reading)" also counts "possible" wording as rain,
     * so we can see whether the change was right.
     */
    fun scores(entries: List<LogEntry>): List<Score> {
        val done = entries.filter { it.rained != null }
        fun score(name: String, said: (LogEntry) -> Boolean?): Score {
            var d = 0; var c = 0; var fa = 0; var m = 0
            for (e in done) {
                val s = said(e) ?: continue
                d++
                when {
                    s == e.rained -> c++
                    s -> fa++
                    else -> m++
                }
            }
            return Score(name, d, c, fa, m)
        }
        fun outlook(e: LogEntry) = e.pagasaSky?.let { PagasaText.rainOutlook(it) }
        val sourceIds = done.flatMap { it.sourceRainPct.keys }.distinct().sorted()
        return buildList {
            add(score("Weather Alarm PH") { it.appRainPct >= RAIN_CALL_PCT })
            sourceIds.forEach { id -> add(score(id.uppercase()) { e -> e.sourceRainPct[id]?.let { it >= RAIN_CALL_PCT } }) }
            add(score("PAGASA") { e -> outlook(e)?.let { it == RainOutlook.LIKELY || it == RainOutlook.VERY_LIKELY } })
            add(score("PAGASA (old reading)") { e -> outlook(e)?.let { it != RainOutlook.UNLIKELY } })
        }.filter { it.days > 0 }
    }

    // ---- storage as JSON text ----

    fun toJson(entries: List<LogEntry>): String = buildJsonArray {
        entries.forEach { e ->
            add(buildJsonObject {
                put("date", e.date.toString())
                put("place", e.placeName)
                put("recordedAt", e.recordedAt.toString())
                put("appRainPct", e.appRainPct)
                put("sourceRainPct", buildJsonObject { e.sourceRainPct.forEach { (k, v) -> put(k, v) } })
                put("pagasaSky", e.pagasaSky)
                put("rained", e.rained)
            })
        }
    }.toString()

    fun fromJson(text: String?): List<LogEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    LogEntry(
                        date = LocalDate.parse(o["date"]!!.jsonPrimitive.content),
                        placeName = o["place"]?.jsonPrimitive?.contentOrNull ?: "",
                        recordedAt = ZonedDateTime.parse(o["recordedAt"]!!.jsonPrimitive.content),
                        appRainPct = o["appRainPct"]!!.jsonPrimitive.int,
                        sourceRainPct = o["sourceRainPct"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap(),
                        pagasaSky = o["pagasaSky"]?.jsonPrimitive?.contentOrNull,
                        rained = o["rained"]?.jsonPrimitive?.booleanOrNull,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
