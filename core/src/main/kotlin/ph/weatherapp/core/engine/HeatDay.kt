package ph.weatherapp.core.engine

import ph.weatherapp.core.model.HeatIndexLevel
import ph.weatherapp.core.model.SourceForecast
import ph.weatherapp.core.pagasa.HeatIndexForecast
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * The day's heat index, for the heat card: peak, its PAGASA category, and the hours at that
 * category. PAGASA iHeatMAP prevails; without it, estimated from the forecast models
 * (median across sources, hour by hour, 6 AM to 6 PM).
 */
data class HeatDay(
    val date: LocalDate,
    val peakC: Int,
    val level: HeatIndexLevel?,          // null = not hazardous (below 27°C)
    val peakHour: Int?,
    val topFrom: Int?,                   // first and last hour at the peak's category
    val topTo: Int?,
    val fromPagasa: Boolean,
)

object HeatDays {

    /** Today's heat until 6 PM; after that, tomorrow's. */
    fun forecast(now: ZonedDateTime, pagasa: HeatIndexForecast?, sources: List<SourceForecast>): HeatDay? {
        val date = if (now.hour < 18) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val hourly: Map<Int, Int> = pagasa?.takeIf { it.date == date && it.hourlyC.isNotEmpty() }?.hourlyC
            ?: modelHourly(date, sources)
        if (hourly.isEmpty()) return null
        val fromPagasa = pagasa?.date == date && pagasa.hourlyC.isNotEmpty()
        val (peakHour, peak) = hourly.maxByOrNull { it.value }!!.toPair()
        val level = HeatIndex.level(peak)
        val top = hourly.filterValues { HeatIndex.level(it) == level }.keys.sorted()
        return HeatDay(date, peak, level, peakHour, top.firstOrNull(), top.lastOrNull(), fromPagasa)
    }

    private fun modelHourly(date: LocalDate, sources: List<SourceForecast>): Map<Int, Int> {
        val byHour = sortedMapOf<Int, MutableList<Double>>()
        for (s in sources) for (p in s.points) {
            val t = p.time.withZoneSameInstant(MANILA)
            if (t.toLocalDate() != date || t.hour !in 6..18) continue
            val c = p.tempC ?: continue
            val rh = p.humidityPct ?: continue
            byHour.getOrPut(t.hour) { mutableListOf() } += HeatIndex.celsius(c, rh)
        }
        return byHour.mapValues { (_, v) -> v.sorted().let { it[it.size / 2] }.roundToInt() }
    }
}
