package ph.weatherapp.core

import ph.weatherapp.core.area.Areas
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.suspension.*
import java.time.LocalDate

private var sbad = 0
private fun ok(name: String, cond: Boolean) { println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) sbad++ }

private const val FEED = """{
 "updatedAt": "2026-11-02T15:40:00+08:00",
 "announcements": [
  {"id": "isabela-city-basilan-2026-11-03-1", "areaId": "isabela-city-basilan", "date": "2026-11-03",
   "classes": true, "work": false, "levels": ["All levels"], "sector": "Public and private", "period": "Whole day",
   "details": "Due to the low pressure area.", "source": "City Government of Isabela", "sourceUrl": "https://www.facebook.com/isabelacity.gov"},
  {"id": "zamboanga-city-2026-11-02-2", "areaId": "zamboanga-city", "date": "2026-11-02",
   "classes": true, "work": true, "levels": ["Preschool", "Elementary"], "sector": "Public only", "period": "Afternoon only",
   "details": null, "source": "City Government of Zamboanga", "sourceUrl": null},
  {"id": "old", "areaId": "isabela-city-basilan", "date": "2026-10-30", "classes": true, "work": false},
  {"id": "broken", "areaId": "isabela-city-basilan", "date": "not-a-date", "classes": true},
  {"id": "empty", "areaId": "isabela-city-basilan", "date": "2026-11-02", "classes": false, "work": false}
 ]
}"""

fun main() {
    val all = SuspensionsJson.parse(FEED)
    ok("parses valid entries, skips broken/empty", all.map { it.id } == listOf("isabela-city-basilan-2026-11-03-1", "zamboanga-city-2026-11-02-2", "old"))

    val today = LocalDate.of(2026, 11, 2)
    val isa = Suspensions.upcoming(all, Areas.ISABELA.id, today)
    ok("Isabela: only tomorrow's (old one dropped)", isa.size == 1 && isa[0].date == today.plusDays(1))
    ok("Isabela: label 'Tomorrow'", Suspensions.dayLabel(isa[0], today, Lang.EN) == "Tomorrow")
    ok("Isabela: title 'No classes'", isa[0].title(Lang.EN) == "No classes" && isa[0].title(Lang.FIL) == "Walang klase")
    ok("Isabela: scope", isa[0].scope() == "All levels · Public and private")

    val zam = Suspensions.upcoming(all, Areas.ZAMBOANGA.id, today)
    ok("Zamboanga: today's afternoon suspension", zam.size == 1 && zam[0].title(Lang.EN) == "No classes and work (afternoon)")
    ok("Zamboanga: scope lists levels", zam[0].scope() == "Preschool, Elementary · Public only")
    ok("no city → nothing", Suspensions.upcoming(all, null, today).isEmpty())
    ok("missing file → nothing", SuspensionsJson.parse(null).isEmpty() && SuspensionsJson.parse("<html>").isEmpty())

    // Areas
    ok("Isabela City hall → Isabela", Areas.nearest(6.705, 121.97) == Areas.ISABELA)
    ok("Zamboanga downtown → Zamboanga", Areas.nearest(6.91, 122.07) == Areas.ZAMBOANGA)
    ok("Manila → no covered city", Areas.nearest(14.6, 120.98) == null)
    ok("Isabela–Zamboanga ≈ 25 km", Areas.distanceKm(6.7032, 121.9838, 6.9214, 122.0790) in 20.0..30.0)

    println(if (sbad == 0) "\nAll suspension checks passed." else "\n$sbad suspension check(s) failed.")
    if (sbad > 0) kotlin.system.exitProcess(1)
}
