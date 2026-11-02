package ph.weatherapp.core.engine

import ph.weatherapp.core.model.*

/**
 * Builds the sentence Android TextToSpeech reads when the alarm rings (premium).
 * Kept short: the user is half-awake.
 */
object VoiceScript {

    fun build(main: WindowForecast, preview: WindowForecast?, recs: List<Recommendation>, lang: Lang): String {
        val sb = StringBuilder()
        val period = periodName(main.window.kind, lang)
        if (lang == Lang.EN) {
            sb.append("Good ${greeting(main)}. For $period: ")
            sb.append("${main.rainChancePct} percent chance of rain, ")
            sb.append("${main.tempMinC} to ${main.tempMaxC} degrees. ")
        } else {
            sb.append("Magandang ${greeting(main, Lang.FIL)}. Para sa $period: ")
            sb.append("${main.rainChancePct} porsyentong tsansa ng ulan, ")
            sb.append("${main.tempMinC} hanggang ${main.tempMaxC} degrees. ")
        }
        recs.take(2).forEach { sb.append(it.text(lang)).append(' ') }
        preview?.let {
            val later = periodName(it.window.kind, lang)
            sb.append(if (lang == Lang.EN) "Later, $later: ${it.rainChancePct} percent chance of rain."
                      else "Mamaya, $later: ${it.rainChancePct} porsyentong tsansa ng ulan.")
        }
        return sb.toString()
            .replace("°C", " degrees")
            .replace(Regex("\\((\\d+)%\\)"), "")   // drop "(60%)": the number was already spoken
            .replace(Regex("\\s+"), " ").replace(" .", ".").trim()
    }

    private fun periodName(k: WindowKind, lang: Lang) = when (k) {
        WindowKind.DAY -> if (lang == Lang.EN) "today, 6 AM to 6 PM" else "ngayong araw, alas-sais ng umaga hanggang alas-sais ng gabi"
        WindowKind.NIGHT -> if (lang == Lang.EN) "tonight, 6 PM to 6 AM" else "ngayong gabi, alas-sais ng gabi hanggang alas-sais ng umaga"
    }

    private fun greeting(f: WindowForecast, lang: Lang = Lang.EN) =
        if (f.window.kind == WindowKind.DAY) (if (lang == Lang.EN) "morning" else "umaga")
        else (if (lang == Lang.EN) "evening" else "gabi")
}
