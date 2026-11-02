package ph.weatherapp.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import ph.weatherapp.core.area.Areas
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
import ph.weatherapp.core.suspension.Suspension
import ph.weatherapp.core.suspension.Suspensions
import ph.weatherapp.core.suspension.SuspensionsJson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Fetches every source for the user's place, keeps the raw responses on the phone, and runs
 * the engine. If the internet is down, the last saved responses are used, so an alarm can
 * still show a forecast with no signal.
 */
object WeatherRepository {

    private const val FEED_ROOT = "https://raw.githubusercontent.com/dansalasim/weather-alarm-app/feed/"
    /** Cities whose morning PAGASA feed exists (the CDRRMO tasks publish Isabela). */
    private val PAGASA_FEED_AREAS = setOf(Areas.ISABELA.id)
    private const val MAX_CACHE_AGE_HOURS = 36L

    data class Forecasts(
        val place: Place,
        val windows: List<WindowForecast>, // free: two 12-hour windows; premium: four 6-hour blocks
        val sixHour: Boolean,
        val currentIsPartial: Boolean,     // first window already started → "until 6 PM"
        val suspensions: List<Suspension>, // this city, today and tomorrow
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
            val place = PlacePrefs.get(context)
            val dir = cacheDir(context, place)
            val shared = cacheDir(context, null)
            val timeout = if (quick) 6_000 else 15_000

            data class Raw(val json: String?, val time: Instant?, val live: Boolean)

            fun fetchOrCache(folder: File, id: String, url: String): Raw = try {
                val body = httpGet(url, timeout)
                save(File(folder, "$id.json"), body)
                Raw(body, Instant.now(), live = true)
            } catch (e: Exception) {
                val f = File(folder, "$id.json")
                if (f.exists() && fresh(f)) Raw(f.readText(), Instant.ofEpochMilli(f.lastModified()), live = false)
                else Raw(null, null, live = false)
            }

            val hasPagasa = place.areaId in PAGASA_FEED_AREAS
            val sourceRaw = ALL_SOURCES.map { s -> async { s to fetchOrCache(dir, s.id, s.url(place.lat, place.lon)) } }
            val regional = async { if (hasPagasa) fetchOrCache(dir, "pagasa-regional", "$FEED_ROOT${place.areaId}/regional.json") else null }
            val heat = async { if (hasPagasa) fetchOrCache(dir, "pagasa-heat", "$FEED_ROOT${place.areaId}/heat.json") else null }
            val susp = async { if (place.areaId != null) fetchOrCache(shared, "suspensions", FEED_ROOT + "suspensions.json") else null }

            val raws = sourceRaw.awaitAll()
            val sources = mutableListOf<SourceForecast>()
            val failures = mutableListOf<String>()
            for ((s, r) in raws) {
                val parsed = r.json?.let { runCatching { s.parse(it, now) }.getOrNull() }
                if (parsed != null) sources += parsed else failures += s.id
            }
            val reg = regional.await()
            val ht = heat.await()
            val sp = susp.await()
            if (hasPagasa && reg?.json == null && ht?.json == null) failures += "PAGASA"
            if (sources.isEmpty()) throw IOException("No forecast available. Check your internet connection.")

            val used = raws.map { it.second } + listOfNotNull(reg, ht)
            val oldest = used.mapNotNull { it.time }.minOrNull() ?: Instant.now()
            compute(
                now = now,
                place = place,
                sources = sources,
                feed = PagasaFeedJson.parse(place.areaId ?: "", reg?.json, ht?.json, now),
                suspensionsJson = sp?.json,
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
        val place = PlacePrefs.get(context)
        val dir = cacheDir(context, place)
        fun read(folder: File, id: String) = File(folder, "$id.json").takeIf { it.exists() && fresh(it) }
        val files = ALL_SOURCES.mapNotNull { s -> read(dir, s.id)?.let { s to it } }
        val sources = files.mapNotNull { (s, f) -> runCatching { s.parse(f.readText(), now) }.getOrNull() }
        if (sources.isEmpty()) return null
        val reg = read(dir, "pagasa-regional")
        val ht = read(dir, "pagasa-heat")
        val sp = read(cacheDir(context, null), "suspensions")
        val oldest = (files.map { it.second } + listOfNotNull(reg, ht)).minOf { it.lastModified() }
        compute(
            now = now,
            place = place,
            sources = sources,
            feed = PagasaFeedJson.parse(place.areaId ?: "", reg?.readText(), ht?.readText(), now),
            suspensionsJson = sp?.readText(),
            forAlarm = forAlarm,
            sixHour = Premium.isActive(context),
            failures = emptyList(),
            fetchedAt = Instant.ofEpochMilli(oldest).atZone(MANILA),
            fromCache = true,
        )
    }.getOrNull()

    private fun compute(
        now: ZonedDateTime,
        place: Place,
        sources: List<SourceForecast>,
        feed: PagasaFeed,
        suspensionsJson: String?,
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
            if (i == 0 && partial) w.copy(start = thisHour) else w
        }
        val forecasts = windows.mapIndexedNotNull { i, w ->
            // The (possibly shortened) first window accepts sources with at least one hour in it.
            val engine = if (i == 0) Consolidator(Consolidator.Config(minHoursPerSource = 1))
                         else Consolidator(Consolidator.Config(minHoursPerSource = if (sixHour) 3 else 6))
            engine.consolidate(w, sources, feed.toAdvisory(w))
        }
        return Forecasts(
            place = place,
            windows = forecasts,
            sixHour = sixHour,
            currentIsPartial = partial,
            suspensions = Suspensions.upcoming(SuspensionsJson.parse(suspensionsJson), place.areaId, now.toLocalDate()),
            failures = failures,
            fetchedAt = fetchedAt,
            fromCache = fromCache,
        )
    }

    /** One folder per place, so switching cities never mixes forecasts. [place] null = shared files. */
    private fun cacheDir(context: Context, place: Place?) =
        File(context.filesDir, "forecast-cache/" + (place?.cacheKey ?: "shared")).apply { mkdirs() }

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
