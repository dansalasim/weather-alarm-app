package ph.weatherapp.core.pagasa

import kotlinx.serialization.json.*
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.model.RainfallWarning
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PAGASA read straight from its website by the phone, so the app never waits for a
 * scheduled task. Three pieces:
 *   1. Regional forecast per province/city: the data behind the location picker on
 *      pagasa.dost.gov.ph/regional-forecast/<prsd> (POST /api/regional/<Region>/<code>).
 *      PAGASA posts the 5 AM forecast there at about 4 to 5 AM.
 *   2. Rainfall warning and thunderstorm advisory: the tabs on the same page.
 *   3. Heat index: iHeatMAP, for the user's exact coordinates.
 * Everything is converted to the same JSON the GitHub feed uses, so the rest of the app
 * (PagasaFeedJson, the cache, the engine) is unchanged.
 */
data class PagasaLocation(val prsd: String, val apiRegion: String, val code: String, val label: String) {
    /** "Isabela City, Zamboanga Peninsula" → "Isabela City". */
    val name: String get() = label.substringBefore(",").trim()
    val pageUrl: String get() = "${PagasaLocations.BASE}/regional-forecast/$prsd"
    val apiUrl: String get() = "${PagasaLocations.BASE}/api/regional/$apiRegion/$code"
}

object PagasaLocations {
    const val BASE = "https://pagasa.dost.gov.ph"
    private fun L(prsd: String, api: String, code: String, label: String) = PagasaLocation(prsd, api, code, label)

    /** The 87 forecast areas in PAGASA's five regional pages (as of November 2026). */
    val ALL: List<PagasaLocation> = listOf(
        L("ncrprsd", "NationalCapitalRegion", "130000000", "Metro Manila"),
        L("ncrprsd", "NationalCapitalRegion", "36900000", "Tarlac"),
        L("ncrprsd", "NationalCapitalRegion", "34900000", "Nueva Ecija"),
        L("ncrprsd", "NationalCapitalRegion", "37100000", "Zambales"),
        L("ncrprsd", "NationalCapitalRegion", "30800000", "Bataan"),
        L("ncrprsd", "NationalCapitalRegion", "35400000", "Pampanga"),
        L("ncrprsd", "NationalCapitalRegion", "31400000", "Bulacan"),
        L("ncrprsd", "NationalCapitalRegion", "42100000", "Cavite"),
        L("ncrprsd", "NationalCapitalRegion", "43400000", "Laguna"),
        L("ncrprsd", "NationalCapitalRegion", "41000000", "Batangas"),
        L("ncrprsd", "NationalCapitalRegion", "45800000", "Rizal"),
        L("ncrprsd", "NationalCapitalRegion", "45600000", "Quezon"),
        L("nlprsd", "NorthernLuzon", "102800000", "Ilocos Norte"),
        L("nlprsd", "NorthernLuzon", "102900000", "Ilocos Sur"),
        L("nlprsd", "NorthernLuzon", "103300000", "La Union"),
        L("nlprsd", "NorthernLuzon", "105500000", "Pangasinan"),
        L("nlprsd", "NorthernLuzon", "200900000", "Batanes"),
        L("nlprsd", "NorthernLuzon", "201500000", "Cagayan"),
        L("nlprsd", "NorthernLuzon", "203100000", "Isabela"),
        L("nlprsd", "NorthernLuzon", "205700000", "Quirino"),
        L("nlprsd", "NorthernLuzon", "205000000", "Nueva Vizcaya"),
        L("nlprsd", "NorthernLuzon", "1400100000", "Abra"),
        L("nlprsd", "NorthernLuzon", "1401100000", "Benguet"),
        L("nlprsd", "NorthernLuzon", "1404400000", "Mountain Province"),
        L("nlprsd", "NorthernLuzon", "1402700000", "Ifugao"),
        L("nlprsd", "NorthernLuzon", "1403200000", "Kalinga"),
        L("nlprsd", "NorthernLuzon", "1408100000", "Apayao"),
        L("nlprsd", "NorthernLuzon", "307700000", "Aurora"),
        L("slprsd", "SouthernLuzon", "50500000", "Albay"),
        L("slprsd", "SouthernLuzon", "501600000", "Camarines Norte"),
        L("slprsd", "SouthernLuzon", "501700000", "Camarines Sur"),
        L("slprsd", "SouthernLuzon", "502000000", "Catanduanes"),
        L("slprsd", "SouthernLuzon", "506200000", "Sorsogon"),
        L("slprsd", "SouthernLuzon", "504100000", "Masbate"),
        L("slprsd", "SouthernLuzon", "804800000", "Northern Samar"),
        L("slprsd", "SouthernLuzon", "1705200000", "Oriental Mindoro"),
        L("slprsd", "SouthernLuzon", "1704000000", "Marinduque"),
        L("slprsd", "SouthernLuzon", "1705900000", "Romblon"),
        L("visprsd", "Visayas", "702200000", "Cebu"),
        L("visprsd", "Visayas", "71200000", "Bohol"),
        L("visprsd", "Visayas", "64500000", "Negros Occidental"),
        L("visprsd", "Visayas", "74600000", "Negros Oriental"),
        L("visprsd", "Visayas", "76100000", "Siquijor"),
        L("visprsd", "Visayas", "803700000", "Leyte"),
        L("visprsd", "Visayas", "806400000", "Southern Leyte"),
        L("visprsd", "Visayas", "807800000", "Biliran"),
        L("visprsd", "Visayas", "806000000", "Samar"),
        L("visprsd", "Visayas", "802600000", "Eastern Samar"),
        L("visprsd", "Visayas", "63000000", "Iloilo"),
        L("visprsd", "Visayas", "67900000", "Guimaras"),
        L("visprsd", "Visayas", "61900000", "Capiz"),
        L("visprsd", "Visayas", "83700000", "Aklan"),
        L("visprsd", "Visayas", "60600000", "Antique"),
        L("visprsd", "Visayas", "1705300000", "Palawan"),
        L("visprsd", "Visayas", "1705100000", "Occidental Mindoro"),
        L("minprsd", "Mindanao", "97300000", "Zamboanga City, Zamboanga Peninsula"),
        L("minprsd", "Mindanao", "907200000", "Zamboanga del Norte, Zamboanga Peninsula"),
        L("minprsd", "Mindanao", "907300000", "Zamboanga del Sur, Zamboanga Peninsula"),
        L("minprsd", "Mindanao", "908300000", "Zamboanga Sibugay, Zamboanga Peninsula"),
        L("minprsd", "Mindanao", "990100000", "Isabela City, Zamboanga Peninsula"),
        L("minprsd", "Mindanao", "1001300000", "Bukidnon, Northern Mindanao"),
        L("minprsd", "Mindanao", "1001800000", "Camiguin, Northern Mindanao"),
        L("minprsd", "Mindanao", "1003500000", "Lanao del Norte, Northern Mindanao"),
        L("minprsd", "Mindanao", "1004200000", "Misamis Occidental, Northern Mindanao"),
        L("minprsd", "Mindanao", "1004300000", "Misamis Oriental, Northern Mindanao"),
        L("minprsd", "Mindanao", "112400000", "Metro Davao, Davao Region"),
        L("minprsd", "Mindanao", "1102300000", "Davao del Norte, Davao Region"),
        L("minprsd", "Mindanao", "1102400000", "Davao del Sur, Davao Region"),
        L("minprsd", "Mindanao", "1102500000", "Davao Oriental, Davao Region"),
        L("minprsd", "Mindanao", "1108200000", "Davao de Oro, Davao Region"),
        L("minprsd", "Mindanao", "1108600000", "Davao Occidental, Davao Region"),
        L("minprsd", "Mindanao", "1204700000", "North Cotabato, SOCCSKSARGEN"),
        L("minprsd", "Mindanao", "1206300000", "South Cotabato, SOCCSKSARGEN"),
        L("minprsd", "Mindanao", "1206500000", "Sultan Kudarat, SOCCSKSARGEN"),
        L("minprsd", "Mindanao", "1208000000", "Sarangani, SOCCSKSARGEN"),
        L("minprsd", "Mindanao", "1600200000", "Agusan del Norte, Caraga Region"),
        L("minprsd", "Mindanao", "1600300000", "Agusan del Sur, Caraga Region"),
        L("minprsd", "Mindanao", "1606700000", "Surigao del Norte, Caraga Region"),
        L("minprsd", "Mindanao", "1606800000", "Surigao del Sur, Caraga Region"),
        L("minprsd", "Mindanao", "1608500000", "Dinagat Islands, Caraga Region"),
        L("minprsd", "Mindanao", "129800000", "Cotabato City, BARMM"),
        L("minprsd", "Mindanao", "1900700000", "Basilan, BARMM"),
        L("minprsd", "Mindanao", "1903600000", "Lanao del Sur, BARMM"),
        L("minprsd", "Mindanao", "906600000", "Sulu, BARMM"),
        L("minprsd", "Mindanao", "1907000000", "Tawi-Tawi, BARMM"),
        L("minprsd", "Mindanao", "1908700000", "Maguindanao del Norte, BARMM"),
        L("minprsd", "Mindanao", "1908800000", "Maguindanao del Sur, BARMM"),
    )

    /** Cities that aren't under a province in PSGC, and renamed provinces → PAGASA's area. */
    private val ALIASES = mapOf(
        "angeles city" to "Pampanga", "bacolod city" to "Negros Occidental", "baguio city" to "Benguet",
        "butuan city" to "Agusan del Norte", "cagayan de oro city" to "Misamis Oriental",
        "cebu city" to "Cebu", "lapu lapu city" to "Cebu", "mandaue city" to "Cebu",
        "davao city" to "Metro Davao", "general santos city" to "South Cotabato",
        "iligan city" to "Lanao del Norte", "iloilo city" to "Iloilo", "lucena city" to "Quezon",
        "olongapo city" to "Zambales", "puerto princesa city" to "Palawan", "tacloban city" to "Leyte",
        "cotabato" to "North Cotabato",
    )

    /**
     * PAGASA's forecast area for a city/municipality, by name: the city itself if PAGASA lists it
     * (Isabela City, Zamboanga City, Cotabato City), else Metro Manila for NCR, else its province.
     * Null when nothing matches by name (the app then picks the nearest area).
     */
    fun match(displayName: String, province: String?, region: String?): PagasaLocation? {
        fun find(n: String?) = n?.let { k -> ALL.firstOrNull { fold(it.name) == fold(k) } }
        if (region == "NCR") return find("Metro Manila")
        return find(displayName.takeIf { it.endsWith(" City") })
            ?: find(ALIASES[fold(displayName)])
            ?: find(ALIASES[fold(province ?: "")] ?: province)
    }

    fun byName(name: String) = ALL.firstOrNull { fold(it.name) == fold(name) }

    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}

object PagasaLive {

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * The /api/regional/... response → feed regional.json (same keys the GitHub feed uses).
     * outlook[0] is the 5 AM regional forecast (today 5 AM–5 PM and tonight 5 PM–5 AM).
     * Its created_at says which day it was issued for. Returns null if the response has no forecast.
     */
    fun regionalFeedJson(apiJson: String, warnings: Warnings, now: ZonedDateTime): String? {
        val root = runCatching { Json.parseToJsonElement(apiJson).jsonObject }.getOrNull() ?: return null
        val f = (root["outlook"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val created = f.s("created_at")?.let { parseTime(it) } ?: return null
        // Entered around 4 AM for the 5 AM issue; anything from 5 PM on counts for the next morning.
        val issueDate = if (created.hour >= 17) created.toLocalDate().plusDays(1) else created.toLocalDate()
        val issuedAt = issueDate.atTime(5, 0).atZone(MANILA)
        val day = f.s("day_description") ?: return null
        fun wind(k: String): String? {
            val speed = f.s("${k}_wind_speed"); val dir = f.s("${k}_wind_direction")
            return when {
                speed != null && dir != null -> "$speed ($dir)"
                else -> speed ?: dir
            }
        }
        val out = buildJsonObject {
            put("part", "regional")
            put("areaId", root.s("psgc_code") ?: root.s("name") ?: "")
            put("source", "DOST-PAGASA Regional Forecast (pagasa.dost.gov.ph)")
            put("location", root.s("name")?.substringBefore(",")?.trim())
            put("issuedAt", issuedAt.format(ISO))
            put("validUntil", issuedAt.plusDays(1).format(ISO))
            put("fetchedAt", now.format(ISO))
            put("skyCondition", day)
            put("tempMinC", f.i("day_low"))
            put("tempMaxC", f.i("day_high"))
            put("tonightSkyCondition", f.s("night_description"))
            put("tonightMinC", f.i("night_low"))
            put("tonightMaxC", f.i("night_high"))
            put("winds", wind("day"))
            put("coastalWaters", f.s("day_coastal_condition"))
            put("synopsis", null as String?)
            put("warnings", buildJsonObject {
                put("tcwsLevel", warnings.tcwsLevel)
                put("rainfallWarning", warnings.rainfallWarning?.name)
                put("thunderstormAdvisory", warnings.thunderstormAdvisory)
                put("validUntil", warnings.validUntil?.format(ISO))
            })
        }
        return out.toString()
    }

    /**
     * Rainfall warning and thunderstorm advisory for one place, from the regional page HTML
     * (tabs #rainfalls and #thunderstorms). A thunderstorm *watch* is not an advisory and is ignored.
     * [province] e.g. "Basilan"; [town] the PSGC name, e.g. "City of Isabela".
     */
    fun warnings(pageHtml: String, province: String?, town: String, now: ZonedDateTime): Warnings {
        val rain = tabText(pageHtml, "rainfalls")
        val storm = tabText(pageHtml, "thunderstorms")
        val keys = placeKeys(province, town)
        var validUntil: ZonedDateTime? = null

        val rainfall = rain?.takeUnless { "no heavy rainfall warning" in it.lowercase() }?.let { t ->
            if (!mentions(t, province, keys)) null
            else listOf(RainfallWarning.RED, RainfallWarning.ORANGE, RainfallWarning.YELLOW)
                .firstOrNull { it.name.lowercase() in t.lowercase() }
        }

        // The tab holds several bulletins, newest first: "Thunderstorm Advisory No. 15 ...",
        // "Thunderstorm Advisory No. 14 ...", "Thunderstorm Watch ...". Only the newest advisory
        // counts (it replaces the earlier ones); a watch is only a 12-hour heads-up.
        val latestAdvisory = storm?.let { t ->
            Regex("Thunderstorm (Advisory|Watch|Information)", RegexOption.IGNORE_CASE).findAll(t)
                .map { it.range.first }.toList().let { starts ->
                    starts.mapIndexed { i, st -> t.substring(st, starts.getOrElse(i + 1) { t.length }) }
                }.firstOrNull { it.lowercase().startsWith("thunderstorm advisory") }
        }
        val thunder = latestAdvisory?.let { t ->
            val issued = issuedAt(t)
            val fresh = issued != null && !now.isBefore(issued.minusMinutes(30)) && now.isBefore(issued.plusHours(3))
            if (fresh && mentions(t, province, keys)) { validUntil = issued!!.plusHours(3); true } else false
        } ?: false

        return Warnings(rainfallWarning = rainfall, thunderstormAdvisory = thunder, validUntil = validUntil)
    }

    /** "City of Isabela" → {"cityofisabela", "isabelacity", "isabela"}. */
    private fun placeKeys(province: String?, town: String): Set<String> {
        val bare = town.removePrefix("City of ").trim()
        return if (town.startsWith("City of")) setOf(squash(town), squash("$bare City"))
               else setOf(squash(town))
    }

    /**
     * PAGASA writes "#Basilan(HadjiMohammadAjul, ..., CityOfIsabela, ...)": a province hashtag,
     * optionally followed by the towns it covers. True if:
     *  - a city is named in any list or as its own hashtag ("CityOfIsabela", "#IsabelaCity"), or
     *  - the user's province is tagged with no town list, or with the user's town in its list.
     * Municipality names alone (e.g. "Magsaysay") only count under their own province.
     */
    private fun mentions(text: String, province: String?, townKeys: Set<String>): Boolean {
        val cityKeys = townKeys.filter { it.startsWith("cityof") || it.endsWith("city") }
        val tags = Regex("#([A-Za-z.\\-]+)(\\(([^)]*)\\))?").findAll(text)
        for (m in tags) {
            val tag = squash(m.groupValues[1])
            val towns = m.groupValues[3].split(",", " and ").map { squash(it) }.filter { it.isNotEmpty() }
            if (tag in cityKeys || towns.any { it in cityKeys }) return true
            if (province != null && (tag == squash(province) || (province == "Cotabato" && tag == "northcotabato"))) {
                if (towns.isEmpty() || towns.any { it in townKeys }) return true
            }
        }
        return false
    }

    private fun squash(s: String) = PagasaLocations.fold(s).replace(" ", "")

    /** Text of <div id="name" ...> ... </div> with tags removed. */
    internal fun tabText(html: String, id: String): String? {
        val start = Regex("<div[^>]*id=\"$id\"[^>]*>").find(html) ?: return null
        val rest = html.substring(start.range.last + 1)
        val end = Regex("<div[^>]*id=\"[a-z-]+\"[^>]*class=\"tab-pane").find(rest)?.range?.first ?: minOf(rest.length, 20_000)
        return rest.substring(0, end)
            .replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&deg;", "°")
            .replace(Regex("[ \t]+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /** "Issued at 01:30 PM 03 November 2026" → time in Manila. */
    internal fun issuedAt(text: String): ZonedDateTime? {
        val m = Regex("Issued at:?\\s*(\\d{1,2}:\\d{2}\\s*[AP]M),?\\s*(\\d{1,2})\\s+([A-Za-z]+),?\\s+(\\d{4})", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return runCatching {
            val t = LocalDateTime.parse(
                "${m.groupValues[2]} ${m.groupValues[3]} ${m.groupValues[4]} ${m.groupValues[1].uppercase().replace(Regex("\\s+"), " ")}",
                DateTimeFormatter.ofPattern("d MMMM yyyy hh:mm a", Locale.ENGLISH),
            )
            t.atZone(MANILA)
        }.getOrNull()
    }

    /**
     * iHeatMAP responses → feed heat.json. [pixelJson] has values [{date, time "HH:MM", value}, ...]
     * covering several days; only today's hours are kept. Null when today has no values.
     */
    fun heatFeedJson(issuanceDate: String?, pixelJson: String, location: String, now: ZonedDateTime): String? {
        val root = runCatching { Json.parseToJsonElement(pixelJson).jsonObject }.getOrNull() ?: return null
        val values = (root["values"] as? JsonArray) ?: return null
        val today = now.toLocalDate()
        val hourly = sortedMapOf<Int, Int>()
        for (v in values) {
            val o = v as? JsonObject ?: continue
            val date = o.s("date")?.let { parseDay(it) } ?: continue
            if (date != today) continue
            val hour = o.s("time")?.take(2)?.toIntOrNull() ?: continue
            val c = (o["value"] as? JsonPrimitive)?.doubleOrNull ?: continue
            hourly[hour] = Math.round(c).toInt()
        }
        if (hourly.isEmpty()) return null
        val issuance = issuanceDate?.let { parseDay(it) }
        val out = buildJsonObject {
            put("part", "heatIndex")
            put("source", "DOST-PAGASA iHeatMAP (ECMWF-based)")
            put("issuedAt", (issuance?.atTime(5, 0)?.atZone(MANILA) ?: now).format(ISO))
            put("issuanceDate", issuance?.toString())
            put("stale", issuance != today)
            put("location", location)
            put("date", today.toString())
            put("hourlyC", buildJsonObject { hourly.forEach { (h, c) -> put(h.toString(), c) } })
        }
        return out.toString()
    }

    /** "2026-11-02T20:14:48.000000Z" (UTC) or "2026-11-03 03:58:12" (Manila; the NCR page uses this). */
    internal fun parseTime(s: String): ZonedDateTime? =
        runCatching { ZonedDateTime.parse(s).withZoneSameInstant(MANILA) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s.trim().take(19).replace(' ', 'T')).atZone(MANILA) }.getOrNull()

    /** "2026-11-03" or "20261103". */
    fun parseDay(s: String): LocalDate? = runCatching {
        val d = s.replace("-", "").take(8)
        LocalDate.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
    }.getOrNull()

    fun issuanceDate(currentDateJson: String): String? = runCatching {
        Json.parseToJsonElement(currentDateJson).jsonObject.s("latest_issuance_date")
    }.getOrNull()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.let { Math.round(it).toInt() }
}
