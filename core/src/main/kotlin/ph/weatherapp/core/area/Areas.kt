package ph.weatherapp.core.area

import kotlin.math.*

/**
 * Cities with local coverage: a PAGASA feed and suspension announcements.
 * Anywhere else still gets the forecast for the phone's exact coordinates.
 */
data class Area(
    val id: String,
    val name: String,
    val province: String,
    val lat: Double,
    val lon: Double,
    val lguName: String,
    /** The LGU's official Facebook page (shown as "LGU announcements"); null until confirmed. */
    val lguPageUrl: String?,
    /** PSA PSGC code, to recognise the city when picked from the full list. */
    val psgc: String,
)

object Areas {
    val ISABELA = Area("isabela-city-basilan", "City of Isabela", "Basilan", 6.7032, 121.9838,
        lguName = "City Government of Isabela", lguPageUrl = "https://www.facebook.com/isabelacity.gov", psgc = "0990101000")
    val ZAMBOANGA = Area("zamboanga-city", "Zamboanga City", "Zamboanga del Sur", 6.9214, 122.0790,
        lguName = "City Government of Zamboanga", lguPageUrl = "https://www.facebook.com/zambocitygovt", psgc = "0931700000")
    val ALL = listOf(ISABELA, ZAMBOANGA)

    fun byId(id: String?): Area? = ALL.firstOrNull { it.id == id }

    fun byPsgc(code: String?): Area? = ALL.firstOrNull { it.psgc == code }

    /** The covered city closest to the phone, if within [maxKm]. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 40.0): Area? =
        ALL.map { it to distanceKm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }?.first

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
