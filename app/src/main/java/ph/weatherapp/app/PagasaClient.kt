package ph.weatherapp.app

import android.content.Context
import ph.weatherapp.core.area.Areas
import ph.weatherapp.core.pagasa.PagasaLive
import ph.weatherapp.core.pagasa.PagasaLocation
import ph.weatherapp.core.pagasa.PagasaLocations
import ph.weatherapp.core.pagasa.Warnings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime

/**
 * Reads PAGASA straight from pagasa.dost.gov.ph and iHeatMAP on the phone, whenever the app
 * refreshes or an alarm is about to ring. No scheduled task or server in between.
 * Results come back in the feed JSON format, so they are cached and read like before.
 */
object PagasaClient {

    private const val UA = "Mozilla/5.0 (Linux; Android) WeatherAlarmPH/0.3"
    private const val HEAT = "https://iheatmap.pagasa.dost.gov.ph/internal/api"

    /** PAGASA's forecast area for this place: by name, else the nearest one. */
    fun areaFor(ctx: Context, place: Place): PagasaLocation? {
        val p = PlaceDirectory.byCode(ctx, place.code)
        p?.let { PagasaLocations.match(it.display, it.province, it.region) }?.let { return it }
        return nearest(ctx, place.lat, place.lon)
    }

    /** Today's regional forecast for [area] plus warnings that name this place. Null on any failure. */
    fun regional(ctx: Context, place: Place, area: PagasaLocation, now: ZonedDateTime, timeoutMs: Int): String? {
        val (html, cookies) = get(area.pageUrl, timeoutMs)
        val api = post(area.apiUrl, cookies, area.pageUrl, timeoutMs)
        val p = PlaceDirectory.byCode(ctx, place.code)
        val town = p?.official ?: place.name
        val warnings = runCatching { PagasaLive.warnings(html, p?.province, town, now) }.getOrDefault(Warnings())
        return PagasaLive.regionalFeedJson(api, warnings, now)
    }

    /** Heat index for the exact coordinates, today's hours. Null if PAGASA has nothing for today. */
    fun heat(place: Place, now: ZonedDateTime, timeoutMs: Int): String? {
        val date = PagasaLive.issuanceDate(get("$HEAT/current-date", timeoutMs).first)
            ?: throw IOException("iHeatMAP: no issuance date")
        val q = "lat=${place.lat}&long=${place.lon}&issuance_date=" + URLEncoder.encode(date, "UTF-8")
        val pixel = get("$HEAT/pixel?$q", timeoutMs).first
        return PagasaLive.heatFeedJson(date, pixel, place.name, now)
    }

    // --- nearest forecast area, from where its cities/provinces are --------------------------

    @Volatile private var centres: Map<PagasaLocation, Pair<Double, Double>>? = null

    private fun nearest(ctx: Context, lat: Double, lon: Double): PagasaLocation? {
        val c = centres ?: buildCentres(ctx).also { centres = it }
        return c.minByOrNull { (_, ll) -> Areas.distanceKm(lat, lon, ll.first, ll.second) }
            ?.takeIf { (_, ll) -> Areas.distanceKm(lat, lon, ll.first, ll.second) < 150 }?.key
    }

    private fun buildCentres(ctx: Context): Map<PagasaLocation, Pair<Double, Double>> {
        val all = PlaceDirectory.all(ctx)
        fun f(s: String?) = PagasaLocations.fold(s ?: "")
        val special = mapOf("metro manila" to "manila city", "metro davao" to "davao city", "north cotabato" to null)
        return PagasaLocations.ALL.mapNotNull { loc ->
            val key = f(loc.name)
            val city = (special[key] ?: key.takeIf { it.endsWith(" city") })?.let { n -> all.firstOrNull { f(it.display) == n } }
            if (city != null) return@mapNotNull loc to (city.lat to city.lon)
            val prov = if (key == "north cotabato") "cotabato" else key
            val members = all.filter { f(it.province) == prov }
            if (members.isEmpty()) null
            else loc to (members.map { it.lat }.sorted()[members.size / 2] to members.map { it.lon }.sorted()[members.size / 2])
        }.toMap()
    }

    // --- HTTP ----------------------------------------------------------------------------------

    /** GET; returns the body and any session cookies. */
    private fun get(url: String, timeoutMs: Int): Pair<String, String?> {
        val c = open(url, timeoutMs)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code $url")
            val cookies = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }.map { it.substringBefore(";") }.joinToString("; ").ifBlank { null }
            return c.inputStream.bufferedReader().use { it.readText() } to cookies
        } finally { c.disconnect() }
    }

    /** POST with an empty form body, the way PAGASA's own page asks for a province. */
    private fun post(url: String, cookies: String?, referer: String, timeoutMs: Int): String {
        val c = open(url, timeoutMs)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Origin", PagasaLocations.BASE)
            c.setRequestProperty("Referer", referer)
            cookies?.let { c.setRequestProperty("Cookie", it) }
            c.outputStream.close()
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code $url")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw IOException("PAGASA sent an empty response")
            return body
        } finally { c.disconnect() }
    }

    private fun open(url: String, timeoutMs: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs + 5_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", UA)
    }
}
