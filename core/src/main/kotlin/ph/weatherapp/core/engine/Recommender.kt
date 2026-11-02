package ph.weatherapp.core.engine

import ph.weatherapp.core.model.*

enum class Lang { EN, FIL }

enum class Priority { URGENT, HIGH, NORMAL }

/**
 * One piece of advice. [en]/[fil] are full sentences (for the voice readout);
 * [shortEn]/[shortFil] are a few words for the screen.
 */
data class Recommendation(
    val code: String,          // stable id, e.g. "UMBRELLA" — the app picks the icon from it
    val priority: Priority,
    val en: String,
    val fil: String,
    val shortEn: String = en,
    val shortFil: String = fil,
) {
    fun text(lang: Lang) = if (lang == Lang.EN) en else fil
    fun short(lang: Lang) = if (lang == Lang.EN) shortEn else shortFil
}

/**
 * Plain rules that turn a window forecast into practical advice.
 * Thresholds are easy to tune after the Isabela City field test.
 */
object Recommender {

    fun recommend(f: WindowForecast): List<Recommendation> {
        val out = mutableListOf<Recommendation>()
        val p = f.pagasa

        // --- Official warnings first ---
        p?.tcwsLevel?.let { lvl ->
            out += Recommendation("TCWS", Priority.URGENT,
                "PAGASA Signal No. $lvl is up in your area. Stay indoors if you can and follow your LGU's advisories.",
                "Nakataas ang Signal No. $lvl ng PAGASA sa inyong lugar. Manatili sa loob ng bahay hangga't maaari at sundin ang abiso ng inyong LGU.",
                "Signal No. $lvl · stay indoors",
                "Signal No. $lvl · manatili sa bahay")
        }
        when (p?.rainfallWarning) {
            RainfallWarning.RED, RainfallWarning.ORANGE -> out += Recommendation("FLOOD", Priority.URGENT,
                "PAGASA ${p.rainfallWarning.name.lowercase()} rainfall warning: flooding is likely. Avoid low-lying and flood-prone areas.",
                "${if (p.rainfallWarning == RainfallWarning.RED) "Pulang" else "Orange na"} babala sa ulan ng PAGASA: malaki ang posibilidad ng baha. Iwasan ang mabababang lugar.",
                "${p.rainfallWarning.name.lowercase().replaceFirstChar { it.uppercase() }} rainfall warning · avoid low areas",
                "${if (p.rainfallWarning == RainfallWarning.RED) "Pulang" else "Orange na"} babala sa ulan · iwas sa mababang lugar")
            RainfallWarning.YELLOW -> out += Recommendation("FLOOD_WATCH", Priority.HIGH,
                "PAGASA yellow rainfall warning: flooding possible in low-lying areas.",
                "Dilaw na babala sa ulan ng PAGASA: posible ang baha sa mabababang lugar.",
                "Yellow rainfall warning · possible flooding",
                "Dilaw na babala sa ulan · posibleng baha")
            null -> {}
        }
        if (p?.thunderstormAdvisory == true) out += Recommendation("THUNDER", Priority.HIGH,
            "Thunderstorms possible. Stay away from open fields and tall trees during lightning.",
            "Posible ang pagkulog at pagkidlat. Lumayo sa bukas na lugar at matataas na puno kapag may kidlat.",
            "Thunderstorms · avoid open areas",
            "May kulog at kidlat · iwas sa bukas na lugar")

        // --- Rain ---
        when {
            f.rainChancePct >= 70 -> out += Recommendation("UMBRELLA", Priority.HIGH,
                "Rain is likely. Bring an umbrella or raincoat and keep gadgets in a waterproof bag.",
                "Malamang umulan. Magdala ng payong o kapote at ilagay ang gadgets sa waterproof na lalagyan.",
                "Bring an umbrella or raincoat",
                "Magdala ng payong o kapote")
            f.rainChancePct >= 40 -> out += Recommendation("UMBRELLA_MAYBE", Priority.NORMAL,
                "There's a chance of rain. Bring a small umbrella just in case.",
                "May tsansang umulan. Magdala ng maliit na payong para sigurado.",
                "Small umbrella, just in case",
                "Maliit na payong, para sigurado")
        }

        // --- Heat (daytime only) ---
        f.heatIndexMaxC?.let { hi ->
            when (HeatIndex.level(hi)) {
                HeatIndexLevel.EXTREME_DANGER, HeatIndexLevel.DANGER -> out += Recommendation("HEAT_DANGER", Priority.URGENT,
                    "Dangerous heat (heat index up to ${hi}°C). Drink lots of water, avoid outdoor activity from 10 AM to 4 PM, and rest in the shade often.",
                    "Mapanganib na init (heat index hanggang ${hi}°C). Uminom ng maraming tubig, iwasan ang gawain sa labas mula 10 AM hanggang 4 PM, at madalas magpahinga sa lilim.",
                    "Dangerous heat · stay out of the sun 10 AM–4 PM",
                    "Mapanganib na init · iwas sa araw 10 AM–4 PM")
                HeatIndexLevel.EXTREME_CAUTION -> out += Recommendation("HEAT_HIGH", Priority.HIGH,
                    "Very hot (heat index up to ${hi}°C). Drink lots of water, bring an umbrella or cap for shade, and avoid the noon sun.",
                    "Napakainit (heat index hanggang ${hi}°C). Uminom ng maraming tubig, magdala ng payong o sombrero, at iwasan ang araw sa tanghali.",
                    "Drink lots of water · avoid noon sun",
                    "Uminom ng maraming tubig · iwas sa araw sa tanghali")
                HeatIndexLevel.CAUTION -> out += Recommendation("HEAT", Priority.NORMAL,
                    "Warm day (heat index up to ${hi}°C). Bring a water bottle.",
                    "Mainit ang araw (heat index hanggang ${hi}°C). Magdala ng tubig.",
                    "Bring water",
                    "Magdala ng tubig")
                null -> {}
            }
        }

        // --- Wind (skip if a Signal message already covers it) ---
        if (p?.tcwsLevel == null) when {
            f.windMaxKph >= 39 || (f.gustMaxKph ?: 0) >= 60 -> out += Recommendation("WIND_STRONG", Priority.HIGH,
                "Strong winds expected. Secure loose items, and take extra care if you ride a motorcycle.",
                "Malakas na hangin ang inaasahan. Itali ang mga bagay na maaaring liparin, at mag-ingat kung nagmo-motor.",
                "Strong winds · careful on motorcycles",
                "Malakas na hangin · ingat sa pagmo-motor")
            f.windMaxKph >= 25 -> out += Recommendation("WIND", Priority.NORMAL,
                "Breezy. Hold on to your umbrella.",
                "Mahangin. Hawakan nang mabuti ang payong.",
                "Breezy · hold your umbrella",
                "Mahangin · hawakan ang payong")
        }

        // --- Cool night ---
        if (f.window.kind == WindowKind.NIGHT && f.tempMinC <= 22) out += Recommendation("JACKET", Priority.NORMAL,
            "Cool night (down to ${f.tempMinC}°C). Bring a jacket.",
            "Malamig ang gabi (hanggang ${f.tempMinC}°C). Magdala ng jacket.",
            "Bring a jacket",
            "Magdala ng jacket")

        // --- Nothing notable ---
        if (out.isEmpty()) out += Recommendation("FAIR", Priority.NORMAL,
            "Fair weather. No umbrella needed.",
            "Maaliwalas ang panahon. Hindi kailangan ng payong.",
            "Fair weather · no umbrella needed",
            "Maaliwalas · hindi kailangan ng payong")

        return out.sortedBy { it.priority.ordinal }
    }
}
