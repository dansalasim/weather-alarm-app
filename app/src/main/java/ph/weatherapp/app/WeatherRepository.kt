package ph.weatherapp.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import ph.weatherapp.core.engine.Consolidator
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.engine.Windows
import ph.weatherapp.core.model.ForecastWindow
import ph.weatherapp.core.model.SourceForecast
import ph.weatherapp.core.model.WindowForecast
import ph.weatherapp.core.pagasa.PagasaFeed
import ph.weatherapp.core.pagasa.PagasaFeedJson
import ph.weatherapp.core.pagasa.toAdvisory
import ph.weatherapp.core.sources.ALL_SOURCES
import ph.weatherapp.core.sources.MetNorway
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Fetches every source, keeps the raw responses on the phone, and runs the engine
 * for two 12-hour windows. If the internet is down, the last saved responses are used,
 * so an alarm can still show a forecast with no signal.
 */
object WeatherRepository {

    // Fixed to Isabela City (Brgy. Sunrise Village) for now. GPS comes in a later build.
    const val PLACE = "City of Isabela, Basilan"
    private const val LAT = 6.7032
    private const val LON = 121.9838
    private const val AREA_ID = "isabela-city-basilan"
    private const val FEED_BASE = "https://raw.githubusercontent.com/dansalasim/weather-alarm-app/feed/$AREA_ID/"
    private const val MAX_CACHE_AGE_HOURS = 36L

    data class Forecasts(
        val windows: List<WindowForecast>, // free: two 12-hour windows; premium: four 6-hour blocks
        val sixHour: Boolean,
        val currentIsPartial: Boolean,     // first window already started → "until 6 PM"
        val failures: List<String>,
        val fetchedAt: ZonedDateTime,      // when the oldest data used was downloaded
        val fromCache: Boolean,
    ) {
        val current: WindowForecast? get() = windows.getOrNull(0)
        val next: WindowForecast? get() = windows.getOrNull(1)
        val later: List<WindowForecast> get() = windows.drop(1)
    }

    /**
     * Downloads fresh data (falling back to the saved copy per source) and computes the forecast.
     * [forAlarm]: pick windows the way the alarm card does (e.g. 5 AM shows today's daytime first).
     * [quick]: shorter timeouts, for when an alarm is ringing.
     */
    suspend fun load(context: Context, forAlarm: Boolean = false, quick: Boolean = false): Forecasts =
        withContext(Dispatchers.IO) {
            val now = ZonedDateTime.now(MANILA)
            val dir = cacheDir(context)
            val timeout = if (quick) 6_000 else 15_000

            data class Raw(val id: String, val json: String?, val time: Instant?, val live: Boolean)

            fun fetchOrCache(id: String, url: String): Raw = try {
                val body = httpGet(url, timeout)
                save(File(dir, "$id.json"), body)
                Raw(id, body, Instant.now(), live = true)
            } catch (e: Exception) {
                val f = File(dir, "$id.json")
                if (f.exists() && fresh(f)) Raw(id, f.readText(), Instant.ofEpochMilli(f.lastModified()), live = false)
                else Raw(id, null, null, live = false)
            }

            val sourceRaw = ALL_SOURCES.map { s -> async { s to fetchOrCache(s.id, s.url(LAT, LON)) } }
            val regional = async { fetchOrCache("pagasa-regional", FEED_BASE + "regional.json") }
            val heat = async { fetchOrCache("pagasa-heat", FEED_BASE + "heat.json") }

            val raws = sourceRaw.awaitAll()
            val sources = mutableListOf<SourceForecast>()
            val failures = mutableListOf<String>()
            for ((s, r) in raws) {
                val parsed = r.json?.let { runCatching { s.parse(it, now) }.getOrNull() }
                if (parsed != null) sources += parsed else failures += s.id
            }
            val reg = regional.await()
            val ht = heat.await()
            if (reg.json == null && ht.json == null) failures += "PAGASA"
            if (sources.isEmpty()) throw IOException("No forecast available. Check your internet connection.")

            val used = raws.map { it.second } + listOf(reg, ht)
            val oldest = used.mapNotNull { it.time }.minOrNull() ?: Instant.now()
            compute(
                now = now,
                sources = sources,
                feed = PagasaFeedJson.parse(AREA_ID, reg.json, ht.json, now),
                forAlarm = forAlarm,
                sixHour = Premium.isActive(context),
                failures = failures,
                fetchedAt = oldest.atZone(MANILA),
                fromCache = used.none { it.live },
            )
        }

    /** Saved data only, no network. Fast enough to call while an alarm is going off. */
    fun loadCached(context: Context, forAlarm: Boolean = true): Forecasts? = runCatching {
        val now = ZonedDateTime.now(MANILA)
        val dir = cacheDir(context)
        fun read(id: String) = File(dir, "$id.json").takeIf { it.exists() && fresh(it) }
        val files = ALL_SOURCES.mapNotNull { s -> read(s.id)?.let { s to it } }
        val sources = files.mapNotNull { (s, f) -> runCatching { s.parse(f.readText(), now) }.getOrNull() }
        if (sources.isEmpty()) return null
        val reg = read("pagasa-regional")
        val ht = read("pagasa-heat")
        val oldest = (files.map { it.second } + listOfNotNull(reg, ht)).minOf { it.lastModified() }
        compute(
            now = now,
            sources = sources,
            feed = PagasaFeedJson.parse(AREA_ID, reg?.readText(), ht?.readText(), now),
            forAlarm = forAlarm,
            sixHour = Premium.isActive(context),
            failures = emptyList(),
            fetchedAt = Instant.ofEpochMilli(oldest).atZone(MANILA),
            fromCache = true,
        )
    }.getOrNull()

    private fun compute(
        now: ZonedDateTime,
        sources: List<SourceForecast>,
        feed: PagasaFeed,
        forAlarm: Boolean,
        sixHour: Boolean,
        failures: List<String>,
        fetchedAt: ZonedDateTime,
        fromCache: Boolean,
    ): Forecasts {
        val plan: List<ForecastWindow> = when {
            sixHour && forAlarm -> Windows.blocks6ForAlarm(now)
            sixHour -> Windows.blocks6(now)
            forAlarm -> Windows.forAlarm(now).toList()
            else -> Windows.current(now).let { listOf(it, Windows.next(it)) }
        }
        // A window that has already started only counts the hours still ahead.
        val first = plan.first()
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val partial = thisHour.isAfter(first.start) && thisHour.isBefore(first.end)
        val windows = plan.mapIndexed { i, w ->
            if (i == 0 && partial) ForecastWindow(w.kind, thisHour, w.end) else w
        }
        val forecasts = windows.mapIndexedNotNull { i, w ->
            // The (possibly shortened) first window accepts sources with at least one hour in it.
            val engine = if (i == 0) Consolidator(Consolidator.Config(minHoursPerSource = 1))
                         else Consolidator(Consolidator.Config(minHoursPerSource = if (sixHour) 3 else 6))
            engine.consolidate(w, sources, feed.toAdvisory(w))
        }
        return Forecasts(
            windows = forecasts,
            sixHour = sixHour,
            currentIsPartial = partial,
            failures = failures,
            fetchedAt = fetchedAt,
            fromCache = fromCache,
        )
    }

    private fun cacheDir(context: Context) = File(context.filesDir, "forecast-cache").apply { mkdirs() }

    private fun fresh(f: File) =
        System.currentTimeMillis() - f.lastModified() < MAX_CACHE_AGE_HOURS * 3_600_000

    /** Write to a temp file first so a half-written file never replaces a good one. */
    private fun save(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs + 5_000
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
