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

    /** The user's LGU page; falls back to the built-in page of the current covered city. */
    fun lgu(ctx: Context, areaId: String?): Pair<String, String>? {
        val name = p(ctx).getString("lgu_name", null)?.takeIf { it.isNotBlank() }
        val url = p(ctx).getString("lgu_url", null)?.takeIf { it.isNotBlank() }
        if (url != null) return (name ?: "Official LGU page") to url
        val a = Areas.byId(areaId) ?: return null
        return a.lguPageUrl?.let { a.lguName to it }
    }

    fun savedLguName(ctx: Context) = p(ctx).getString("lgu_name", null) ?: ""
    fun savedLguUrl(ctx: Context) = p(ctx).getString("lgu_url", null) ?: ""

    fun save(ctx: Context, nickname: String, lguName: String, lguUrl: String?) {
        p(ctx).edit()
            .putString("nickname", nickname.trim())
            .putString("lgu_name", lguName.trim())
            .putString("lgu_url", lguUrl)
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
