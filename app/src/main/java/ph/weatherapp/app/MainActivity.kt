package ph.weatherapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.weatherapp.core.engine.HeatIndex
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.engine.Priority
import ph.weatherapp.core.engine.Recommendation
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

// ---------- Palette (navy + teal, like the CDRRMO infographics) ----------

private val Navy = Color(0xFF0A1628)
private val NavyTop = Color(0xFF15304F)
private val CardNavy = Color(0xFF12233A)
private val Tile = Color(0xFF1A2F4A)
private val Teal = Color(0xFF4FD1C5)
private val Amber = Color(0xFFFBBF24)
private val Orange = Color(0xFFF97316)
private val Red = Color(0xFFEF4444)
private val Muted = Color(0xFF8DA2BA)
private val Faint = Color(0xFF5B7090)

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
    data class Ready(val r: WeatherRepository.Forecasts) : UiState
    data class Failed(val message: String) : UiState
}

@Composable
fun ForecastScreen() {
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    var lang by remember { mutableStateOf(Lang.EN) }
    val scope = rememberCoroutineScope()
    val en = lang == Lang.EN

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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: place + time, language and refresh buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null, tint = Teal, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("City of Isabela", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val sub = (state as? UiState.Ready)?.r?.fetchedAt
                    ?.format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")) ?: "Basilan"
                Text(sub, color = Muted, fontSize = 12.sp)
            }
            HeaderButton(Icons.Rounded.Translate, if (en) "FIL" else "EN") { lang = if (en) Lang.FIL else Lang.EN }
            Spacer(Modifier.width(8.dp))
            HeaderButton(Icons.Rounded.Refresh, null) { refresh() }
        }

        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal, strokeWidth = 3.dp)
            }
            is UiState.Failed -> ErrorCard(s.message, en) { refresh() }
            is UiState.Ready -> {
                val r = s.r
                val urgent = listOfNotNull(r.current, r.next)
                    .flatMap { Recommender.recommend(it) }
                    .firstOrNull { it.code in setOf("TCWS", "FLOOD", "FLOOD_WATCH", "THUNDER") }
                urgent?.let { AlertBanner(it, lang) }

                r.current?.let { HeroCard(it, lang, partial = r.currentIsPartial) }
                r.next?.let { NextCard(it, lang) }

                Text(
                    "DOST-PAGASA · ECMWF · GFS · Open-Meteo · MET Norway (CC BY 4.0)" +
                        if (r.failures.isNotEmpty()) "\nNot reached: " + r.failures.joinToString(", ") { it.substringBefore(":") } else "",
                    color = Faint, fontSize = 10.sp, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

// ---------- Components ----------

@Composable
private fun HeaderButton(icon: ImageVector, label: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Tile) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = Teal, modifier = Modifier.size(18.dp))
            label?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AlertBanner(rec: Recommendation, lang: Lang) {
    val c = if (rec.priority == Priority.URGENT) Red else Amber
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = 0.15f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = c, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(rec.short(lang), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun HeroCard(f: WindowForecast, lang: Lang, partial: Boolean) {
    val en = lang == Lang.EN
    val isDay = f.window.kind == WindowKind.DAY
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(NavyTop, CardNavy)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Period + agreement
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                icon = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay,
                text = periodLabel(f.window.kind, current = true, partial = partial, en = en),
            )
            Spacer(Modifier.weight(1f))
            ConfidenceDot(f.confidence, en)
        }

        // Big rain chance + glyph
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${f.rainChancePct}%", fontSize = 60.sp, fontWeight = FontWeight.Bold, lineHeight = 62.sp)
                Text(rainLabel(f.rainChancePct, en), color = rainColor(f.rainChancePct), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(
                skyIcon(f), null,
                tint = rainColor(f.rainChancePct).copy(alpha = 0.9f),
                modifier = Modifier.size(72.dp),
            )
        }

        // Metric tiles
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(Icons.Rounded.Thermostat, "${f.tempMinC}–${f.tempMaxC}°", if (en) "Temp" else "Temp", Modifier.weight(1f))
            MetricTile(Icons.Rounded.Air, "${f.windMaxKph}", if (en) "Wind km/h" else "Hangin km/h", Modifier.weight(1f))
            val hi = f.heatIndexMaxC
            if (hi != null) {
                MetricTile(Icons.Rounded.DeviceThermostat, "$hi°", "Heat index", Modifier.weight(1f), heatColor(HeatIndex.level(hi)))
            } else {
                MetricTile(Icons.Rounded.WaterDrop, "${f.rainChancePct}%", if (en) "Rain" else "Ulan", Modifier.weight(1f))
            }
        }

        // PAGASA wording
        f.pagasa?.skyCondition?.let { PagasaLine(it) }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        // Advice
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Recommender.recommend(f).forEach { AdviceRow(it, lang) }
        }
    }
}

@Composable
private fun NextCard(f: WindowForecast, lang: Lang) {
    val en = lang == Lang.EN
    val isDay = f.window.kind == WindowKind.DAY
    val top = Recommender.recommend(f).first()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardNavy)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                icon = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay,
                text = periodLabel(f.window.kind, current = false, partial = false, en = en),
            )
            Spacer(Modifier.weight(1f))
            ConfidenceDot(f.confidence, en)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(skyIcon(f), null, tint = rainColor(f.rainChancePct), modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${f.rainChancePct}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(rainLabel(f.rainChancePct, en), color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${f.tempMinC}–${f.tempMaxC}°C", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${f.windMaxKph} km/h", color = Muted, fontSize = 12.sp)
            }
        }
        AdviceRow(top, lang)
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Teal.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConfidenceDot(c: Confidence, en: Boolean) {
    val (color, label) = when (c) {
        Confidence.HIGH -> Teal to (if (en) "Sources agree" else "Nagkakasundo")
        Confidence.MEDIUM -> Amber to (if (en) "Mostly agree" else "Halos magkasundo")
        Confidence.LOW -> Orange to (if (en) "Sources differ" else "Magkaiba")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun MetricTile(icon: ImageVector, value: String, label: String, modifier: Modifier, valueColor: Color = Color.White) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Tile)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PagasaLine(sky: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "PAGASA", color = Navy, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 2.dp)
                .background(Teal, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(sky, color = Muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdviceRow(rec: Recommendation, lang: Lang) {
    val accent = when (rec.priority) {
        Priority.URGENT -> Red
        Priority.HIGH -> Amber
        Priority.NORMAL -> Teal
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(accent.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(adviceIcon(rec.code), null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(rec.short(lang), fontSize = 15.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ErrorCard(message: String, en: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardNavy)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Muted, modifier = Modifier.size(40.dp))
        Text(message, color = Muted, fontSize = 14.sp)
        Button(onClick = onRetry) { Text(if (en) "Try again" else "Subukan muli") }
    }
}

// ---------- Mapping helpers ----------

private fun periodLabel(kind: WindowKind, current: Boolean, partial: Boolean, en: Boolean): String = when {
    kind == WindowKind.DAY && current && partial -> if (en) "Today · until 6 PM" else "Ngayon · hanggang 6 PM"
    kind == WindowKind.DAY && current -> if (en) "Today · 6 AM–6 PM" else "Ngayon · 6 AM–6 PM"
    kind == WindowKind.DAY -> if (en) "Tomorrow · 6 AM–6 PM" else "Bukas · 6 AM–6 PM"
    current && partial -> if (en) "Tonight · until 6 AM" else "Ngayong gabi · hanggang 6 AM"
    else -> if (en) "Tonight · 6 PM–6 AM" else "Ngayong gabi · 6 PM–6 AM"
}

private fun rainLabel(pct: Int, en: Boolean) = when {
    pct >= 80 -> if (en) "Rain very likely" else "Malamang na malamang umulan"
    pct >= 60 -> if (en) "Rain likely" else "Malamang umulan"
    pct >= 40 -> if (en) "Rain possible" else "Posibleng umulan"
    pct >= 20 -> if (en) "Rain unlikely" else "Malabong umulan"
    else -> if (en) "No rain expected" else "Walang inaasahang ulan"
}

private fun rainColor(pct: Int) = when {
    pct >= 60 -> Teal
    pct >= 40 -> Color(0xFF93C5FD)
    else -> Amber
}

private fun heatColor(level: HeatIndexLevel?) = when (level) {
    HeatIndexLevel.EXTREME_DANGER, HeatIndexLevel.DANGER -> Red
    HeatIndexLevel.EXTREME_CAUTION -> Orange
    HeatIndexLevel.CAUTION -> Amber
    null -> Color.White
}

private fun skyIcon(f: WindowForecast): ImageVector {
    val sky = f.pagasa?.skyCondition?.lowercase() ?: ""
    val stormy = f.pagasa?.thunderstormAdvisory == true || f.pagasa?.tcwsLevel != null ||
        (f.rainChancePct >= 60 && "thunder" in sky)
    return when {
        stormy -> Icons.Rounded.Thunderstorm
        f.rainChancePct >= 40 -> Icons.Rounded.Umbrella
        f.rainChancePct >= 20 -> Icons.Rounded.Cloud
        f.window.kind == WindowKind.DAY -> Icons.Rounded.WbSunny
        else -> Icons.Rounded.NightsStay
    }
}

private fun adviceIcon(code: String): ImageVector = when (code) {
    "TCWS" -> Icons.Rounded.Cyclone
    "FLOOD", "FLOOD_WATCH" -> Icons.Rounded.Flood
    "THUNDER" -> Icons.Rounded.Thunderstorm
    "UMBRELLA", "UMBRELLA_MAYBE" -> Icons.Rounded.Umbrella
    "HEAT_DANGER", "HEAT_HIGH" -> Icons.Rounded.LocalDrink
    "HEAT" -> Icons.Rounded.WaterDrop
    "WIND_STRONG", "WIND" -> Icons.Rounded.Air
    "JACKET" -> Icons.Rounded.Checkroom
    "FAIR" -> Icons.Rounded.CheckCircle
    else -> Icons.Rounded.Info
}
