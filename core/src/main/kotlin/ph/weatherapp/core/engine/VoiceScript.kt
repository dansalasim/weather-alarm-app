package ph.weatherapp.core.engine

import ph.weatherapp.core.model.*
import java.time.ZonedDateTime

/**
 * Builds the sentence Android TextToSpeech reads after the alarm is dismissed (premium).
 * Kept short: the user is half-awake.
 */
object VoiceScript {

    fun build(
        main: WindowForecast,
        preview: WindowForecast?,
        recs: List<Recommendation>,
        lang: Lang,
        now: ZonedDateTime = ZonedDateTime.now(MANILA),
        name: String? = null,
    ): String {
        val en = lang == Lang.EN
        val sb = StringBuilder()
        val greeting = when (now.hour) {
            in 0..11 -> if (en) "Good morning" else "Magandang umaga"
            in 12..17 -> if (en) "Good afternoon" else "Magandang hapon"
            else -> if (en) "Good evening" else "Magandang gabi"
        }
        sb.append(greeting)
        name?.takeIf { it.isNotBlank() }?.let { sb.append(", ").append(it.trim()) }
        sb.append(". ")
        if (en) {
            sb.append("For ${Periods.spoken(main.window, lang)}: ")
            sb.append("${main.rainChancePct} percent chance of rain, ")
            sb.append("${main.tempMinC} to ${main.tempMaxC} degrees. ")
        } else {
            sb.append("Para sa ${Periods.spoken(main.window, lang)}: ")
            sb.append("${main.rainChancePct} porsyentong tsansa ng ulan, ")
            sb.append("${main.tempMinC} hanggang ${main.tempMaxC} degrees. ")
        }
        recs.take(2).forEach { sb.append(it.text(lang)).append(' ') }
        preview?.let {
            val later = Periods.spoken(it.window, lang)
            sb.append(if (en) "Later, $later: ${it.rainChancePct} percent chance of rain."
                      else "Mamaya, $later: ${it.rainChancePct} porsyentong tsansa ng ulan.")
        }
        return sb.toString()
            .replace("°C", " degrees")
            .replace(Regex("\\((\\d+)%\\)"), "")
            .replace(Regex("\\s+"), " ").replace(" .", ".").trim()
    }
}
