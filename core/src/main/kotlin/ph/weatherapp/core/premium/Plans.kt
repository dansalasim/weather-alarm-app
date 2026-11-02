package ph.weatherapp.core.premium

/**
 * Subscription plans. Product IDs must match the ones created in
 * Google Play Console → Monetize → Subscriptions.
 */
enum class Plan(val productId: String, val pricePhp: Int, val trialDays: Int) {
    MONTHLY("premium_monthly", 49, 0),
    ANNUAL("premium_annual", 500, 7),     // ≈ ₱42/month, about 15% off 12 × ₱49
}

enum class Feature {
    // Free
    TWELVE_HOUR_FORECAST, ALARMS, RECOMMENDATIONS, PAGASA_WARNINGS, BANNER_ADS,
    // Premium
    SIX_HOUR_FORECAST, VOICE_READOUT, NO_ADS,
    // Premium, coming later
    SUSPENSION_ALERTS, HOURLY_AND_CURRENT,
}

object Entitlements {
    private val FREE = setOf(
        Feature.TWELVE_HOUR_FORECAST, Feature.ALARMS, Feature.RECOMMENDATIONS,
        Feature.PAGASA_WARNINGS, Feature.BANNER_ADS,
    )
    private val PREMIUM = setOf(
        Feature.SIX_HOUR_FORECAST, Feature.ALARMS, Feature.RECOMMENDATIONS,
        Feature.PAGASA_WARNINGS, Feature.VOICE_READOUT, Feature.NO_ADS,
        Feature.SUSPENSION_ALERTS, Feature.HOURLY_AND_CURRENT,
    )
    val COMING_SOON = setOf(Feature.SUSPENSION_ALERTS, Feature.HOURLY_AND_CURRENT)

    /** PAGASA warnings stay free for everyone. */
    fun has(feature: Feature, isPremium: Boolean): Boolean =
        feature in (if (isPremium) PREMIUM else FREE) && feature !in COMING_SOON

    fun maxAlarms(@Suppress("UNUSED_PARAMETER") isPremium: Boolean): Int = 2

    /** Paywall line: "Save ₱88 a year". */
    fun annualSavingsPhp(): Int = Plan.MONTHLY.pricePhp * 12 - Plan.ANNUAL.pricePhp
}
