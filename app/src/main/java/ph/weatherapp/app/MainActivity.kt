package ph.weatherapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.weatherapp.core.engine.HeatIndex
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.engine.Priority
import ph.weatherapp.core.engine.Recommender
import ph.weatherapp.core.model.Confidence
import ph.weatherapp.core.model.HeatIndexLevel
import ph.weatherapp.core.model.WindowForecast
import ph.weatherapp.core.model.WindowKind
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { ForecastScreen() } }
    }
}

// ---------- Theme (navy + teal, like the CDRRMO infographics) ----------

private val Navy = Color(0xFF0B1A2E)
private val CardNavy = Color(0xFF13263F)
private val Teal = Color(0xFF4FD1C5)
private val Amber = Color(0xFFFBBF24)
private val Danger = Color(0xFFF87171)
private val Muted = Color(0xFF9FB3C8)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal, onPrimary = Navy,
            background = Navy, onBackground = Color.White,
            surface = CardNavy, onSurface = Color.White,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy) { content() }
    }
}

// ---------- Screen ----------

private sealed interface UiState {
    data object Loading : UiState
    data class Ready(val r: WeatherRepository.Result) : UiState
    data class Failed(val message: String) : UiState
}

@Composable
fun ForecastScreen() {
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    var lang by remember { mutableStateOf(Lang.EN) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            state = UiState.Loading
            state = try {
                UiState.Ready(WeatherRepository.load())
            } catch (e: Exception) {
                UiState.Failed(e.message ?: "Something went wrong.")
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(WeatherRepository.PLACE, color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (lang == Lang.EN) "Weather forecast" else "Ulat panahon", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { lang = if (lang == Lang.EN) Lang.FIL else Lang.EN }) {
                Text(if (lang == Lang.EN) "Filipino" else "English")
            }
        }

        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal)
            }
            is UiState.Failed -> {
                Text(s.message, color = Danger)
                Button(onClick = { refresh() }) { Text(if (lang == Lang.EN) "Try again" else "Subukan muli") }
            }
            is UiState.Ready -> {
                val r = s.r
                Text(
                    (if (lang == Lang.EN) "Updated " else "Na-update ") + r.fetchedAt.format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")),
                    color = Muted, fontSize = 12.sp,
                )
                r.current?.let { WindowCard(it, lang, isNow = true, partial = r.currentIsPartial) }
                r.next?.let { WindowCard(it, lang, isNow = false, partial = false) }

                OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (lang == Lang.EN) "Refresh" else "I-refresh")
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Data: Open-Meteo · MET Norway (CC BY 4.0) · PAGASA warnings coming soon",
                        color = Muted.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 13.sp,
                    )
                    if (r.failures.isNotEmpty()) {
                        Text("Not reached: " + r.failures.joinToString("; "), color = Amber.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowCard(f: WindowForecast, lang: Lang, isNow: Boolean, partial: Boolean) {
    val en = lang == Lang.EN
    val title = when {
        f.window.kind == WindowKind.DAY && isNow && partial -> if (en) "REST OF TODAY · UNTIL 6 PM" else "NATITIRANG ORAS NGAYON · HANGGANG 6 PM"
        f.window.kind == WindowKind.DAY && isNow -> if (en) "TODAY · 6 AM–6 PM" else "NGAYONG ARAW · 6 AM–6 PM"
        f.window.kind == WindowKind.DAY -> if (en) "TOMORROW · 6 AM–6 PM" else "BUKAS · 6 AM–6 PM"
        isNow && partial -> if (en) "REST OF TONIGHT · UNTIL 6 AM" else "NATITIRANG ORAS NGAYONG GABI · HANGGANG 6 AM"
        else -> if (en) "TONIGHT · 6 PM–6 AM" else "NGAYONG GABI · 6 PM–6 AM"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardNavy),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.rainChancePct}%", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(rainLabel(f.rainChancePct, en), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        color = if (f.rainChancePct >= 60) Teal else Color.White)
                    Text(if (en) "chance of rain" else "tsansa ng ulan", color = Muted, fontSize = 13.sp)
                }
            }

            Stat(if (en) "Temperature" else "Temperatura", "${f.tempMinC}–${f.tempMaxC}°C")
            Stat(if (en) "Wind" else "Hangin", (if (en) "up to " else "hanggang ") + "${f.windMaxKph} km/h" +
                (f.gustMaxKph?.let { if (en) ", gusts $it" else ", bugso $it" } ?: ""))
            f.heatIndexMaxC?.let { hi ->
                val level = HeatIndex.level(hi)
                Stat("Heat index", "$hi°C" + (level?.let { " · " + levelName(it, en) } ?: ""),
                    valueColor = if (level != null && level >= HeatIndexLevel.EXTREME_CAUTION) Amber else Color.White)
            }
            Stat(
                if (en) "Sources agree" else "Pagkakasundo ng sources",
                when (f.confidence) {
                    Confidence.HIGH -> if (en) "High" else "Mataas"
                    Confidence.MEDIUM -> if (en) "Medium" else "Katamtaman"
                    Confidence.LOW -> if (en) "Low — treat with care" else "Mababa — mag-ingat"
                },
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Recommender.recommend(f).forEach { rec ->
                val c = if (rec.priority == Priority.URGENT) Amber else Color.White
                Row {
                    Text("•", color = Teal, fontSize = 15.sp, modifier = Modifier.width(16.dp))
                    Text(rec.text(lang), color = c, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun rainLabel(pct: Int, en: Boolean) = when {
    pct >= 80 -> if (en) "Rain very likely" else "Malamang na malamang umulan"
    pct >= 60 -> if (en) "Rain likely" else "Malamang umulan"
    pct >= 40 -> if (en) "Rain possible" else "Posibleng umulan"
    pct >= 20 -> if (en) "Rain unlikely" else "Malabong umulan"
    else -> if (en) "No rain expected" else "Walang inaasahang ulan"
}

private fun levelName(l: HeatIndexLevel, en: Boolean) = when (l) {
    HeatIndexLevel.CAUTION -> if (en) "Caution" else "Pag-iingat"
    HeatIndexLevel.EXTREME_CAUTION -> if (en) "Extreme caution" else "Matinding pag-iingat"
    HeatIndexLevel.DANGER -> if (en) "Danger" else "Panganib"
    HeatIndexLevel.EXTREME_DANGER -> if (en) "Extreme danger" else "Matinding panganib"
}
