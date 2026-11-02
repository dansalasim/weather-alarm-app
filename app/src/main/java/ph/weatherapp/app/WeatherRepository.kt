package ph.weatherapp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.weatherapp.core.engine.Consolidator
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.engine.Windows
import ph.weatherapp.core.model.ForecastWindow
import ph.weatherapp.core.model.SourceForecast
import ph.weatherapp.core.model.WindowForecast
import ph.weatherapp.core.pagasa.PagasaFeedJson
import ph.weatherapp.core.pagasa.toAdvisory
import ph.weatherapp.core.sources.ALL_SOURCES
import ph.weatherapp.core.sources.MetNorway
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Fetches every source, then runs the engine for the current and next 12-hour window. */
object WeatherRepository {

    // v0.1: fixed to Isabela City (Brgy. Sunrise Village). GPS comes in a later build.
    const val PLACE = "City of Isabela, Basilan"
    private const val LAT = 6.7032
    private const val LON = 121.9838
    private const val AREA_ID = "isabela-city-basilan"
    private const val FEED_BASE = "https://raw.githubusercontent.com/dansalasim/weather-alarm-app/feed/$AREA_ID/"

    data class Result(
        val current: WindowForecast?,
        val next: WindowForecast?,
        val currentIsPartial: Boolean,
        val failures: List<String>,
        val fetchedAt: ZonedDateTime,
    )

    suspend fun load(): Result = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now(MANILA)
        val sources = mutableListOf<SourceForecast>()
        val failures = mutableListOf<String>()
        for (s in ALL_SOURCES) {
            try {
                sources += s.parse(httpGet(s.url(LAT, LON)), now)
            } catch (e: Exception) {
                failures += "${s.id}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (sources.isEmpty()) throw IOException("No weather source could be reached. Check your internet connection.")

        // PAGASA feed published each morning by the CDRRMO tasks. Optional: the app still works without it.
        val regionalJson = runCatching { httpGet(FEED_BASE + "regional.json") }.getOrNull()
        val heatJson = runCatching { httpGet(FEED_BASE + "heat.json") }.getOrNull()
        if (regionalJson == null && heatJson == null) failures += "PAGASA feed"
        val feed = PagasaFeedJson.parse(AREA_ID, regionalJson, heatJson, now)

        // The current window only counts the hours still ahead ("rest of today").
        val cur = Windows.current(now)
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val partial = thisHour.isAfter(cur.start)
        val remaining = ForecastWindow(cur.kind, if (partial) thisHour else cur.start, cur.end)
        val next = Windows.next(cur)
        val engine = Consolidator(Consolidator.Config(minHoursPerSource = 1))

        Result(
            current = engine.consolidate(remaining, sources, feed.toAdvisory(remaining)),
            next = Consolidator().consolidate(next, sources, feed.toAdvisory(next)),
            currentIsPartial = partial,
            failures = failures,
            fetchedAt = now,
        )
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", MetNorway.USER_AGENT)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
