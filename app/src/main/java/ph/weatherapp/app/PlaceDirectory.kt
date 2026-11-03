package ph.weatherapp.app

import android.content.Context
import org.json.JSONObject
import ph.weatherapp.core.area.Areas
import java.text.Normalizer

/**
 * Every Philippine city and municipality (PSA PSGC, 30 June 2026) with coordinates,
 * bundled in assets/ph-places.json so search works offline.
 */
data class PhPlace(
    val code: String,
    val official: String,       // PSGC name, e.g. "City of Lamitan"
    val isCity: Boolean,
    val province: String?,      // null for independent cities and Metro Manila
    val region: String,
    val lat: Double,
    val lon: Double,
) {
    /** Everyday name: "City of Lamitan" → "Lamitan City". */
    val display: String = official.removePrefix("City of ").let { if (official.startsWith("City of ")) "$it City" else it }

    /** Where it is: province, "Metro Manila", or the region for independent cities. */
    val areaLabel: String = when {
        region == "NCR" -> "Metro Manila"
        province != null -> province
        else -> "$region · independent city"
    }

    /** Province grouping for browsing. */
    val group: String = when {
        region == "NCR" -> "Metro Manila"
        province != null -> province
        else -> "Independent cities · $region"
    }

    /** City with PAGASA feed and suspension alerts. */
    val covered: Boolean get() = Areas.byPsgc(code) != null

    internal val searchText: String = PlaceDirectory.fold("$display $official $areaLabel")
}

object PlaceDirectory {
    @Volatile private var cache: List<PhPlace>? = null

    fun all(ctx: Context): List<PhPlace> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): List<PhPlace> = runCatching {
        val text = ctx.assets.open("ph-places.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("places")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PhPlace(
                code = o.getString("c"),
                official = o.getString("n"),
                isCity = o.optString("t") == "c",
                province = if (o.isNull("p")) null else o.getString("p"),
                region = o.getString("r"),
                lat = o.getDouble("la"),
                lon = o.getDouble("lo"),
            )
        }.sortedBy { it.display }
    }.getOrDefault(emptyList())

    /** Province groups for browsing, alphabetical, each with its places. */
    fun groups(ctx: Context): List<Pair<String, List<PhPlace>>> =
        all(ctx).groupBy { it.group }.toList().sortedBy { (g, _) -> (if (g.startsWith("Independent")) "zz" else "") + g }

    /** Name search, accent-insensitive ("las pinas" finds Las Piñas). Names starting with the query come first. */
    fun search(ctx: Context, query: String, limit: Int = 80): List<PhPlace> {
        val q = fold(query)
        if (q.isBlank()) return emptyList()
        val words = q.split(" ").filter { it.isNotBlank() }
        return all(ctx).asSequence()
            .filter { p -> words.all { p.searchText.contains(it) } }
            .sortedWith(compareBy({ !fold(it.display).startsWith(q) }, { it.display }))
            .take(limit).toList()
    }

    fun byCode(ctx: Context, code: String?): PhPlace? = code?.let { c -> all(ctx).firstOrNull { it.code == c } }

    /** Closest city/municipality centre to a GPS fix (within [maxKm]). */
    fun nearest(ctx: Context, lat: Double, lon: Double, maxKm: Double = 60.0): PhPlace? =
        all(ctx).minByOrNull { Areas.distanceKm(lat, lon, it.lat, it.lon) }
            ?.takeIf { Areas.distanceKm(lat, lon, it.lat, it.lon) <= maxKm }

    internal fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
