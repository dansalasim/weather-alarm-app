package ph.weatherapp.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ph.weatherapp.core.area.Area
import ph.weatherapp.core.area.Areas
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the forecast is for. [areaId] is set when the place is a covered city
 * (PAGASA feed + suspension announcements); otherwise it's just coordinates ("Near you").
 */
data class Place(val name: String, val lat: Double, val lon: Double, val areaId: String?) {
    val cacheKey: String
        get() = areaId ?: String.format(Locale.US, "gps-%.2f-%.2f", lat, lon)

    companion object {
        fun of(a: Area, lat: Double = a.lat, lon: Double = a.lon) = Place(a.name, lat, lon, a.id)
    }
}

object PlacePrefs {
    private const val FILE = "place"

    fun get(ctx: Context): Place {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        if (lat == null || lon == null) return Place.of(Areas.ISABELA)
        return Place(p.getString("name", null) ?: Areas.ISABELA.name, lat, lon, p.getString("area", null))
    }

    /** true = follow the phone's location; false = a city the user picked. */
    fun isAuto(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("auto", true)

    fun pickCity(ctx: Context, a: Area) {
        save(ctx, Place.of(a))
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("auto", false).apply()
    }

    fun useLocation(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("auto", true).apply()
    }

    /** Save a detected location: the nearest covered city if within 40 km, otherwise "Near you". */
    fun saveDetected(ctx: Context, lat: Double, lon: Double): Place {
        val area = Areas.nearest(lat, lon)
        val place = area?.let { Place.of(it, lat, lon) } ?: Place("Near you", lat, lon, null)
        save(ctx, place)
        return place
    }

    private fun save(ctx: Context, p: Place) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("name", p.name)
            .putString("lat", p.lat.toString())
            .putString("lon", p.lon.toString())
            .putString("area", p.areaId)
            .apply()
    }
}

/** Approximate location (city level is enough), with an 8-second limit. */
object Locator {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Detects the location and saves it if following location; returns true if the place changed. */
    suspend fun refreshPlace(ctx: Context): Boolean {
        if (!PlacePrefs.isAuto(ctx) || !hasPermission(ctx)) return false
        val before = PlacePrefs.get(ctx)
        val loc = current(ctx) ?: return false
        val after = PlacePrefs.saveDetected(ctx, loc.latitude, loc.longitude)
        return after.cacheKey != before.cacheKey
    }

    @Suppress("MissingPermission")
    private suspend fun current(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        fun lastKnown(): Location? = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }

        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return lastKnown()
        }
        if (Build.VERSION.SDK_INT < 30) return lastKnown()
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                        if (cont.isActive) cont.resume(loc ?: lastKnown())
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: lastKnown()
    }
}
