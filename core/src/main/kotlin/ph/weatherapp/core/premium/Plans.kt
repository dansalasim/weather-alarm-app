package ph.weatherapp.core.premium

/**
 * Subscription plans. Product IDs must match the ones you create in
 * Google Play Console → Monetize → Subscriptions.
 */
enum class Plan(val productId: String, val pricePhp: Int, val trialDays: Int) {
    MONTHLY("premium_monthly", 99, 0),
    ANNUAL("premium_annual", 949, 7),     // 12 × ₱99 = ₱1,188 less 20% ≈ ₱949
}

enum class Feature {
    // Free for everyone — PAGASA warnings are never paywalled
    DAY_NIGHT_FORECAST, TWO_ALARMS, RECOMMENDATIONS, PAGASA_WARNINGS,
    // Premium
    VOICE_READOUT, UNLIMITED_ALARMS, MULTIPLE_LOCATIONS, RAIN_SOON_ALERT, THREE_DAY_OUTLOOK, NO_ADS,
}

object Entitlements {
    private val FREE = setOf(
        Feature.DAY_NIGHT_FORECAST, Feature.TWO_ALARMS,
        Feature.RECOMMENDATIONS, Feature.PAGASA_WARNINGS,
    )

    fun has(feature: Feature, isPremium: Boolean): Boolean = isPremium || feature in FREE

    fun maxAlarms(isPremium: Boolean): Int = if (isPremium) Int.MAX_VALUE else 2

    /** Paywall line: "Save ₱239 a year". */
    fun annualSavingsPhp(): Int = Plan.MONTHLY.pricePhp * 12 - Plan.ANNUAL.pricePhp
}
