package ph.weatherapp.core.suspension

import kotlinx.serialization.json.*
import ph.weatherapp.core.engine.Lang
import java.time.LocalDate

/**
 * One class/work suspension announcement, entered by hand from the official LGU post
 * (feed branch: suspensions.json, added through the GitHub issue form).
 */
data class Suspension(
    val id: String,
    val areaId: String,
    val date: LocalDate,
    val classes: Boolean,
    val work: Boolean,
    val levels: List<String>,     // e.g. ["All levels"] or ["Elementary", "High school"]
    val sector: String,           // "Public and private" | "Public only" | "Private only"
    val period: String,           // "Whole day" | "Morning only" | "Afternoon only"
    val details: String?,
    val source: String?,
    val sourceUrl: String?,
) {
    /** "No classes" / "No work" / "No classes and work", plus the period if not the whole day. */
    fun title(lang: Lang): String {
        val en = lang == Lang.EN
        val base = when {
            classes && work -> if (en) "No classes and work" else "Walang pasok sa klase at trabaho"
            classes -> if (en) "No classes" else "Walang klase"
            else -> if (en) "No work" else "Walang pasok sa trabaho"
        }
        val p = when (period.lowercase()) {
            "morning only" -> if (en) " (morning)" else " (umaga)"
            "afternoon only" -> if (en) " (afternoon)" else " (hapon)"
            else -> ""
        }
        return base + p
    }

    /** e.g. "All levels · Public and private" */
    fun scope(): String =
        (if (classes) listOf(levels.joinToString(", ").ifBlank { "All levels" }) else emptyList())
            .plus(sector)
            .joinToString(" · ")
}

object SuspensionsJson {
    fun parse(json: String?): List<Suspension> {
        if (json == null) return emptyList()
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val arr = root["announcements"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching {
                Suspension(
                    id = o.s("id") ?: return@mapNotNull null,
                    areaId = o.s("areaId") ?: return@mapNotNull null,
                    date = LocalDate.parse(o.s("date")),
                    classes = o.b("classes"),
                    work = o.b("work"),
                    levels = (o["levels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    sector = o.s("sector") ?: "Public and private",
                    period = o.s("period") ?: "Whole day",
                    details = o.s("details"),
                    source = o.s("source"),
                    sourceUrl = o.s("sourceUrl"),
                )
            }.getOrNull()?.takeIf { it.classes || it.work }
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
}

object Suspensions {
    /** Announcements for this city for today and tomorrow, today first. */
    fun upcoming(all: List<Suspension>, areaId: String?, today: LocalDate): List<Suspension> =
        if (areaId == null) emptyList()
        else all.filter { it.areaId == areaId && !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(1)) }
            .sortedBy { it.date }

    fun dayLabel(s: Suspension, today: LocalDate, lang: Lang): String {
        val en = lang == Lang.EN
        return when (s.date) {
            today -> if (en) "Today" else "Ngayon"
            today.plusDays(1) -> if (en) "Tomorrow" else "Bukas"
            else -> s.date.toString()
        }
    }
}
