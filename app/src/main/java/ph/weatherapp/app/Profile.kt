package ph.weatherapp.app

import android.content.Context
import ph.weatherapp.core.area.Areas

/**
 * What the user entered at first launch, plus the disclaimer preference.
 * Stored only on the phone.
 */
object Profile {
    private const val FILE = "profile"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSetUp(ctx: Context) = p(ctx).getBoolean("setup_done", false)

    fun nickname(ctx: Context): String? = p(ctx).getString("nickname", null)?.takeIf { it.isNotBlank() }

    /*
     * LGU page, saved per place: what the user entered for THIS city/municipality.
     * Changing location never carries another town's LGU over. Isabela City and Zamboanga City
     * have a built-in official page; anywhere else with nothing entered → null (card asks to add one).
     */
    private fun key(place: Place) = place.code ?: place.cacheKey

    fun lgu(ctx: Context, place: Place): Pair<String, String>? {
        val k = key(place)
        val url = p(ctx).getString("lgu_url_$k", null)?.takeIf { it.isNotBlank() }
        if (url != null) return (p(ctx).getString("lgu_name_$k", null)?.takeIf { it.isNotBlank() } ?: "Official LGU page") to url
        val a = Areas.byId(place.areaId) ?: return null
        return a.lguPageUrl?.let { a.lguName to it }
    }

    /** What was entered for this place, if anything (for the setup form). */
    fun savedLgu(ctx: Context, place: Place): Pair<String, String>? {
        val k = key(place)
        val name = p(ctx).getString("lgu_name_$k", null) ?: return null
        return name to (p(ctx).getString("lgu_url_$k", null) ?: "")
    }

    fun save(ctx: Context, nickname: String, place: Place, lguName: String, lguUrl: String?) {
        val k = key(place)
        p(ctx).edit()
            .putString("nickname", nickname.trim())
            .putString("lgu_name_$k", lguName.trim())
            .putString("lgu_url_$k", lguUrl ?: "")
            .remove("lgu_name").remove("lgu_url")   // old single-LGU entry (before per-place)
            .putBoolean("setup_done", true)
            .apply()
    }

    fun hideDisclaimer(ctx: Context) = p(ctx).getBoolean("hide_disclaimer", false)
    fun setHideDisclaimer(ctx: Context) = p(ctx).edit().putBoolean("hide_disclaimer", true).apply()

    /**
     * Accepts "facebook.com/page", "https://m.facebook.com/page", "fb.com/page".
     * Returns a clean https link, or null if it isn't a Facebook link.
     */
    fun normalizeFacebookUrl(input: String): String? {
        val raw = input.trim().ifBlank { return null }
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val ok = Regex("^https?://(www\\.|m\\.|web\\.)?(facebook\\.com|fb\\.com)/[^\\s]+$", RegexOption.IGNORE_CASE)
            .matches(withScheme)
        return if (ok) withScheme.replaceFirst("http://", "https://") else null
    }
}
