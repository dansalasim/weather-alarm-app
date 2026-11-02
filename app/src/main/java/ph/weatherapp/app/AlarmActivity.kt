package ph.weatherapp.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ph.weatherapp.app.alarm.AlarmNotifier
import ph.weatherapp.app.alarm.AlarmPrefs
import ph.weatherapp.app.alarm.AlarmScheduler
import ph.weatherapp.app.alarm.AlarmSpec
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.engine.MANILA
import ph.weatherapp.core.engine.Recommender
import ph.weatherapp.core.engine.VoiceScript
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The full-screen card shown when an alarm rings, including on the lock screen.
 * The sound comes from the alarm notification and keeps repeating until Snooze or Dismiss.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, AlarmSpec.MAIN)
        speaker = if (Premium.isActive(this)) Speaker(this) else null
        setContent {
            AppTheme {
                AlarmScreen(
                    id = id,
                    speaker = speaker,
                    onSnooze = {
                        AlarmNotifier.stop(this, id)
                        AlarmScheduler.snooze(this, id)
                        finish()
                    },
                    onStopRinging = { AlarmNotifier.stop(this, id) },
                    onClose = { finish() },
                )
            }
        }
    }

    private var speaker: Speaker? = null

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    id: Int,
    speaker: Speaker?,
    onSnooze: () -> Unit,
    onStopRinging: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val lang = AlarmPrefs.lang(ctx)
    val en = lang == Lang.EN
    val spec = AlarmPrefs.get(ctx, id)

    // Saved forecast right away; then try a quick refresh (6-second timeout).
    var data by remember { mutableStateOf(WeatherRepository.loadCached(ctx, forAlarm = true)) }
    LaunchedEffect(Unit) {
        runCatching { WeatherRepository.load(ctx, forAlarm = true, quick = true) }.getOrNull()?.let { data = it }
    }
    var speaking by remember { mutableStateOf(false) }

    // Premium: after Dismiss, read the forecast aloud, then close. Free: just close.
    fun readAloud(thenClose: Boolean) {
        val d = data ?: return onClose()
        val main = d.current ?: return onClose()
        if (speaker == null) return onClose()
        speaking = true
        speaker.speak(lang, { l -> VoiceScript.build(main, d.next, Recommender.recommend(main), l) }) {
            speaking = false
            if (thenClose) onClose()
        }
    }
    fun dismiss() {
        onStopRinging()
        if (speaker != null && data?.current != null) readAloud(thenClose = true) else onClose()
    }

    var now by remember { mutableStateOf(ZonedDateTime.now(MANILA)) }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); now = ZonedDateTime.now(MANILA) }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Clock
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(now.format(DateTimeFormatter.ofPattern("h:mm")), fontSize = 72.sp, fontWeight = FontWeight.Bold, lineHeight = 74.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(now.format(DateTimeFormatter.ofPattern("a")), fontSize = 22.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Alarm, null, tint = Teal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(spec.label(lang) + " · " + now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Teal, fontSize = 14.sp)
                }
            }

            val d = data
            if (d == null) {
                Text(
                    if (en) "No saved forecast yet. Connect to the internet and open the app once."
                    else "Wala pang naka-save na ulat. Kumonekta sa internet at buksan ang app.",
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                if (Premium.isActive(ctx)) d.suspensions.forEach { SuspensionCard(it, lang) }
                urgentWarning(d.current, d.next)?.let { AlertBanner(it, lang) }
                if (spec.isMain) {
                    d.current?.let { HeroCard(it, lang, partial = d.currentIsPartial) }
                    if (d.sixHour) {
                        if (d.later.isNotEmpty()) BlocksCard(d.later, lang)
                    } else {
                        d.next?.let { NextCard(it, lang) }
                    }
                } else {
                    // Quick check before leaving: one compact card with the top three tips.
                    d.current?.let { NextCard(it, lang, partial = d.currentIsPartial, adviceCount = 3) }
                }
                if (d.fromCache) {
                    Text(
                        (if (en) "Offline · forecast saved " else "Offline · naka-save ") +
                            d.fetchedAt.format(DateTimeFormatter.ofPattern("h:mm a")),
                        color = Faint, fontSize = 11.sp,
                    )
                }
            }
        }

        if (speaking) {
            Button(
                onClick = { speaker?.stop(); speaking = false; onClose() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp).height(60.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy),
            ) {
                Icon(Icons.Rounded.RecordVoiceOver, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (en) "Reading forecast… tap to stop" else "Binabasa ang ulat… pindutin para itigil",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            return@Column
        }

        // Big buttons, always visible at the bottom
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Rounded.Snooze, null, tint = Teal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("${AlarmScheduler.SNOOZE_MINUTES} min", color = Teal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { dismiss() },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy),
            ) {
                if (speaker != null) {
                    Icon(Icons.Rounded.RecordVoiceOver, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (en) "Dismiss" else "Itigil", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
