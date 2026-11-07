package ph.weatherapp.app

import android.os.Bundle
import com.google.android.gms.ads.MobileAds
import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ph.weatherapp.app.alarm.AlarmNotifier
import ph.weatherapp.app.alarm.AlarmPrefs
import ph.weatherapp.app.alarm.AlarmScheduler
import ph.weatherapp.app.alarm.AlarmSpec
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import ph.weatherapp.core.area.Area
import ph.weatherapp.core.area.Areas
import ph.weatherapp.core.suspension.Suspension
import ph.weatherapp.core.suspension.Suspensions
import ph.weatherapp.core.engine.Periods
import ph.weatherapp.core.premium.Entitlements
import ph.weatherapp.core.premium.Plan
import ph.weatherapp.core.engine.Lang
import ph.weatherapp.core.engine.Priority
import ph.weatherapp.core.engine.Recommendation
import ph.weatherapp.core.engine.Recommender
import ph.weatherapp.core.model.Confidence
import ph.weatherapp.core.model.HeatIndexLevel
import ph.weatherapp.core.model.WindowForecast
import ph.weatherapp.core.model.WindowKind
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so permission status re-checks after returning from Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmNotifier.ensureChannel(this)
        AlarmScheduler.scheduleAll(this)
        Thread { MobileAds.initialize(this) {} }.start()   // banner ads (free version)
        // Landing page on each fresh launch (not on rotation), unless "Don't show again".
        val showLanding = savedInstanceState == null && !Profile.hideDisclaimer(this)
        setContent { AppTheme { AppRoot(showLanding, resumes.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

private enum class Stage { LANDING, SETUP, EDIT_PROFILE, MAIN }

@Composable
private fun AppRoot(showLanding: Boolean, resumes: Int) {
    val ctx = LocalContext.current.applicationContext
    fun afterLanding() = if (Profile.isSetUp(ctx)) Stage.MAIN else Stage.SETUP
    var stage by rememberSaveable { mutableStateOf(if (showLanding) Stage.LANDING else afterLanding()) }
    when (stage) {
        Stage.LANDING -> LandingScreen(
            onUnderstand = { stage = afterLanding() },
            onDontShowAgain = { Profile.setHideDisclaimer(ctx); stage = afterLanding() },
        )
        Stage.SETUP -> SetupScreen(editing = false) { stage = Stage.MAIN }
        Stage.EDIT_PROFILE -> SetupScreen(editing = true) { stage = Stage.MAIN }
        Stage.MAIN -> ForecastScreen(resumes) { stage = Stage.EDIT_PROFILE }
    }
}

// ---------- Palette (navy + teal, like the CDRRMO infographics) ----------

internal val Navy = Color(0xFF0A1628)
internal val NavyTop = Color(0xFF15304F)
internal val CardNavy = Color(0xFF12233A)
internal val Tile = Color(0xFF1A2F4A)
internal val Teal = Color(0xFF4FD1C5)
internal val Amber = Color(0xFFFBBF24)
internal val Orange = Color(0xFFF97316)
internal val Red = Color(0xFFEF4444)
internal val Muted = Color(0xFF8DA2BA)
internal val Faint = Color(0xFF5B7090)

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
fun ForecastScreen(resumes: Int, onEditProfile: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    // Show the saved forecast instantly, then refresh from the internet.
    var state by remember {
        mutableStateOf<UiState>(WeatherRepository.loadCached(ctx, forAlarm = false)?.let { UiState.Ready(it) } ?: UiState.Loading)
    }
    var lang by remember { mutableStateOf(AlarmPrefs.lang(ctx)) }
    var premium by remember { mutableStateOf(Premium.isActive(ctx)) }
    val scope = rememberCoroutineScope()
    val en = lang == Lang.EN

    var showPlaces by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showPlans by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            if (state !is UiState.Ready) state = UiState.Loading
            if (Locator.refreshPlace(ctx)) {
                // Moved to another city: show its saved forecast (if any) while downloading.
                state = WeatherRepository.loadCached(ctx, forAlarm = false)?.let { UiState.Ready(it) } ?: UiState.Loading
            }
            state = try {
                UiState.Ready(WeatherRepository.load(ctx))
            } catch (e: Exception) {
                (state as? UiState.Ready) ?: UiState.Failed(e.message ?: "Something went wrong.")
            }
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PlacePrefs.useLocation(ctx)
        refresh()
    }
    LaunchedEffect(Unit) { refresh() }

    if (showPlaces) PlacePicker(
        lang = lang,
        onDismiss = { showPlaces = false },
        onUseLocation = {
            showPlaces = false
            PlacePrefs.useLocation(ctx)
            if (Locator.hasPermission(ctx)) refresh() else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        },
        onSearch = { showPlaces = false; showSearch = true },
        onEditProfile = { showPlaces = false; onEditProfile() },
    )
    if (showPlans) PlansDialog(lang) { showPlans = false }
    if (showSearch) PlaceSearchDialog(lang, onDismiss = { showSearch = false }) { picked ->
        showSearch = false
        PlacePrefs.pickPlace(ctx, picked)
        state = WeatherRepository.loadCached(ctx, forAlarm = false)?.let { UiState.Ready(it) } ?: UiState.Loading
        refresh()
    }

    fun setPremium(on: Boolean) {
        Premium.setPreview(ctx, on)
        premium = on
        WeatherRepository.loadCached(ctx, forAlarm = false)?.let { state = UiState.Ready(it) }
        refresh()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: place + time, language and refresh buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            val place = (state as? UiState.Ready)?.r?.place ?: PlacePrefs.get(ctx)
            Icon(if (PlacePrefs.isAuto(ctx) && Locator.hasPermission(ctx)) Icons.Rounded.MyLocation else Icons.Rounded.LocationOn,
                null, tint = Teal, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { showPlaces = true }) {
                Profile.nickname(ctx)?.let { name ->
                    Text(greeting(en) + ", " + name, color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(place.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.ExpandMore, null, tint = Muted, modifier = Modifier.size(20.dp))
                    if (premium) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.WorkspacePremium, "Premium", tint = Amber, modifier = Modifier.size(18.dp))
                    }
                }
                val sub = (state as? UiState.Ready)?.r?.fetchedAt
                    ?.format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")) ?: ""
                Text(sub, color = Muted, fontSize = 12.sp)
            }
            if (!premium) {
                Surface(onClick = { showPlans = true }, shape = RoundedCornerShape(12.dp), color = Amber.copy(alpha = 0.18f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WorkspacePremium, "Premium", tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Premium", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            HeaderButton(Icons.Rounded.Translate, if (en) "FIL" else "EN") {
                lang = if (en) Lang.FIL else Lang.EN
                AlarmPrefs.setLang(ctx, lang)
            }
            Spacer(Modifier.width(8.dp))
            HeaderButton(Icons.Rounded.Refresh, null) { refresh() }
        }

        FullScreenBanner(lang, resumes)

        when (val s = state) {
            is UiState.Loading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal, strokeWidth = 3.dp)
            }
            is UiState.Failed -> ErrorCard(s.message, en) { refresh() }
            is UiState.Ready -> {
                val r = s.r
                if (premium) r.suspensions.forEach { SuspensionCard(it, lang) }
                urgentWarning(r.current, r.next)?.let { (rec, until) -> AlertBanner(rec, lang, until) }

                r.current?.let { HeroCard(it, lang, partial = r.currentIsPartial) }
                if (r.sixHour) {
                    if (r.later.isNotEmpty()) BlocksCard(r.later, lang)
                } else {
                    r.next?.let { NextCard(it, lang) }
                    if (!premium) PremiumTeaser(lang) { showPlans = true }
                }

                if (premium) Profile.lgu(ctx, r.place.areaId)?.let { (name, url) -> LguCard(name, url, lang) }
                AccuracyCard(lang, resumes)
                AlarmsSection(lang, resumes)
                PremiumCard(premium, lang) { setPremium(it) }

                val uiCtx = LocalContext.current
                Text(
                    if (en) "Privacy policy" else "Patakaran sa privacy",
                    color = Teal.copy(alpha = 0.8f), fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        runCatching {
                            uiCtx.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/dansalasim/weather-alarm-app/blob/main/PRIVACY.md")))
                        }
                    }.padding(vertical = 2.dp),
                )
                Text(
                    "DOST-PAGASA (live) · ECMWF · GFS · Open-Meteo · MET Norway (CC BY 4.0)" +
                        (if (r.fromCache) "\n" + (if (en) "Offline · saved " else "Offline · naka-save ") +
                            r.fetchedAt.format(DateTimeFormatter.ofPattern("h:mm a")) else "") +
                        (if (r.failures.isNotEmpty() && !r.fromCache) "\nNot reached: " + r.failures.joinToString(", ") else ""),
                    color = Faint, fontSize = 10.sp, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
        if (!premium) BannerAd()
    }
}

// ---------- Alarms section ----------

@Composable
private fun AlarmsSection(lang: Lang, resumes: Int) {
    val ctx = LocalContext.current
    val en = lang == Lang.EN
    var alarms by remember { mutableStateOf(AlarmPrefs.all(ctx)) }
    var permTick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    fun update(a: AlarmSpec) {
        AlarmPrefs.save(ctx, a)
        AlarmScheduler.schedule(ctx, a)
        alarms = AlarmPrefs.all(ctx)
    }
    fun notifAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Re-read each time the screen resumes (e.g. back from Settings).
    val check = resumes + permTick
    val anyOn = alarms.any { it.enabled }
    val needNotif = remember(check) { !notifAllowed() }
    val needExact = remember(check) { !AlarmScheduler.canScheduleExact(ctx) }
    val needFullScreen = remember(check) { !AlarmNotifier.canUseFullScreen(ctx) }
    val next = remember(alarms, check) { AlarmScheduler.nextAlarmText(ctx) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardNavy)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Alarm, null, tint = Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (en) "Alarms" else "Mga alarm", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            next?.let { Text((if (en) "Next " else "Susunod ") + it, color = Muted, fontSize = 12.sp) }
        }

        alarms.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(Teal.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (a.isMain) Icons.Rounded.WbTwilight else Icons.Rounded.DirectionsWalk, null,
                        tint = Teal, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            TimePickerDialog(ctx, { _, h, m ->
                                if (!notifAllowed() && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                update(a.copy(hour = h, minute = m, enabled = true))
                            }, a.hour, a.minute, false).show()
                        },
                ) {
                    Text(a.timeText(), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = if (a.enabled) Color.White else Muted)
                    Text(a.label(lang) + (if (a.isMain) (if (en) " · full forecast" else " · buong ulat") else (if (en) " · quick check" else " · mabilis na tingin")),
                        color = Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = a.enabled,
                    onCheckedChange = { on ->
                        if (on && !notifAllowed() && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        update(a.copy(enabled = on))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Navy, checkedTrackColor = Teal),
                )
            }
        }

        if (anyOn && needNotif) PermissionRow(
            if (en) "Allow notifications so alarms can ring" else "Payagan ang notifications para tumunog ang alarm",
        ) { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if (anyOn && needExact && Build.VERSION.SDK_INT >= 31) PermissionRow(
            if (en) "Allow exact alarms so they ring on time" else "Payagan ang eksaktong oras ng alarm",
        ) { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
        if (anyOn && needFullScreen && Build.VERSION.SDK_INT >= 34) PermissionRow(
            if (en) "Allow full-screen alarms on the lock screen" else "Payagan ang full-screen na alarm",
        ) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))) }

        TextButton(onClick = { AlarmScheduler.test(ctx, AlarmSpec.MAIN) }, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = Teal, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (en) "Test: ring in 10 seconds" else "Subukan: tutunog sa 10 segundo", color = Teal, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PermissionRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Amber.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = Amber, modifier = Modifier.size(18.dp))
    }
}

// ---------- Premium: next 18 hours in 6-hour blocks ----------

@Composable
internal fun BlocksCard(blocks: List<WindowForecast>, lang: Lang) {
    val en = lang == Lang.EN
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardNavy)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Icon(Icons.Rounded.Schedule, null, tint = Teal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (en) "Next ${blocks.size * 6} hours" else "Susunod na ${blocks.size * 6} oras",
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        blocks.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(skyIcon(f), null, tint = rainColor(f.rainChancePct), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(periodLabel(f, partial = false, en = en), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Recommender.recommend(f).first().short(lang), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${f.rainChancePct}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = rainColor(f.rainChancePct))
                    Text("${f.tempMinC}–${f.tempMaxC}°", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------- Upgrade / premium preview ----------

@Composable
private fun PremiumCard(premium: Boolean, lang: Lang, onPreview: (Boolean) -> Unit) {
    val en = lang == Lang.EN
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2340), CardNavy)))
            .border(1.dp, Amber.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WorkspacePremium, null, tint = Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (premium) "Premium" else (if (en) "Go Premium" else "Mag-Premium"),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!premium) Text("₱${Plan.MONTHLY.pricePhp}/" + (if (en) "mo" else "buwan"), color = Amber, fontWeight = FontWeight.SemiBold)
        }
        if (!premium) {
            PremiumPerks(en)
            Spacer(Modifier.height(2.dp))
            PlanButtons(lang)
        }
        // Testing only, until Google Play Billing is connected. Not in the Play Store version.
        if (BuildConfig.PREMIUM_PREVIEW) Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (en) "Premium preview" else "Silipin ang Premium", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (en) "Testing only · no payment" else "Pang-test lang · walang bayad", color = Muted, fontSize = 11.sp)
            }
            Switch(
                checked = premium,
                onCheckedChange = onPreview,
                colors = SwitchDefaults.colors(checkedThumbColor = Navy, checkedTrackColor = Amber),
            )
        }
    }
}

/** The two plans. Tapping one starts Google Play checkout once the app is on Play Store. */
@Composable
private fun PlanButtons(lang: Lang) {
    val ctx = LocalContext.current
    val en = lang == Lang.EN
    fun choose(plan: Plan) {
        // TODO(billing): launch Google Play Billing for plan.productId when the Play account is ready.
        Toast.makeText(
            ctx,
            if (en) "Subscriptions open when the app is on Google Play." else "Magbubukas ang subscription kapag nasa Google Play na ang app.",
            Toast.LENGTH_LONG,
        ).show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Annual first: best value, with the free trial
        Button(
            onClick = { choose(Plan.ANNUAL) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Navy),
        ) {
            Column(Modifier.weight(1f)) {
                Text("₱${Plan.ANNUAL.pricePhp} / " + (if (en) "year" else "taon"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (en) "${Plan.ANNUAL.trialDays} days free, then ₱${Plan.ANNUAL.pricePhp}"
                    else "${Plan.ANNUAL.trialDays} araw na libre, saka ₱${Plan.ANNUAL.pricePhp}",
                    fontSize = 12.sp,
                )
            }
            Text(
                (if (en) "Save ₱" else "Tipid ₱") + Entitlements.annualSavingsPhp(),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber,
                modifier = Modifier.background(Navy, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        OutlinedButton(
            onClick = { choose(Plan.MONTHLY) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.6f)),
        ) {
            Text("₱${Plan.MONTHLY.pricePhp} / " + (if (en) "month" else "buwan"), color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (en) "Cancel anytime in Google Play." else "Puwedeng i-cancel kahit kailan sa Google Play.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Opened from the header button or the "See Premium" link. */
@Composable
private fun PlansDialog(lang: Lang, onDismiss: () -> Unit) {
    val en = lang == Lang.EN
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(CardNavy)
                .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Amber, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (en) "Go Premium" else "Mag-Premium", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, if (en) "Close" else "Isara", tint = Muted) }
            }
            PremiumPerks(en)
            Spacer(Modifier.height(4.dp))
            PlanButtons(lang)
        }
    }
}

@Composable
private fun PremiumPerks(en: Boolean) {
    Perk(Icons.Rounded.Schedule, if (en) "6-hour forecast blocks" else "Ulat kada 6 na oras")
    Perk(Icons.Rounded.RecordVoiceOver, if (en) "Alarm reads the forecast aloud" else "Binabasa ng alarm ang ulat")
    Perk(Icons.Rounded.Block, if (en) "No ads" else "Walang ads")
    Perk(Icons.Rounded.Campaign, if (en) "Class and work suspension alerts" else "Abiso ng suspensyon ng klase at trabaho")
    Perk(Icons.Rounded.AccountBalance, if (en) "LGU announcements page" else "Pahina ng anunsyo ng LGU")
    Perk(Icons.Rounded.Timeline, if (en) "Soon: hourly and current weather" else "Malapit na: kada oras at kasalukuyang panahon")
}

/** One line under the free 12-hour forecast pointing to what Premium adds. */
@Composable
private fun PremiumTeaser(lang: Lang, onClick: () -> Unit) {
    val en = lang == Lang.EN
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Amber.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (en) "6-hour blocks, voice alarm and suspension alerts" else "Ulat kada 6 na oras, boses sa alarm at abiso ng suspensyon",
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Text(if (en) "See Premium" else "Tingnan", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Icon(Icons.Rounded.ChevronRight, null, tint = Amber, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Perk(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp)
    }
}

// ---------- Suspensions (premium) ----------

@Composable
internal fun SuspensionCard(sp: Suspension, lang: Lang) {
    val ctx = LocalContext.current
    val en = lang == Lang.EN
    val today = java.time.LocalDate.now(ph.weatherapp.core.engine.MANILA)
    val isToday = sp.date == today
    val accent = if (isToday) Red else Amber
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.13f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (sp.classes) Icons.Rounded.School else Icons.Rounded.Work, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Suspensions.dayLabel(sp, today, lang).uppercase(), color = accent, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(sp.title(lang), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(sp.scope(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        sp.details?.let { Text(it, color = Muted, fontSize = 13.sp, lineHeight = 17.sp) }
        val src = sp.source ?: (if (en) "Official announcement" else "Opisyal na anunsyo")
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (sp.sourceUrl != null) Modifier.clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sp.sourceUrl))) }
                } else Modifier)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((if (en) "Source: " else "Pinagmulan: ") + src, color = Muted, fontSize = 12.sp)
            if (sp.sourceUrl != null) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.OpenInNew, null, tint = Muted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ---------- LGU announcements (premium) ----------

@Composable
private fun LguCard(lguName: String, lguUrl: String, lang: Lang) {
    val ctx = LocalContext.current
    val en = lang == Lang.EN
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardNavy)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Campaign, null, tint = Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (en) "LGU announcements" else "Mga anunsyo ng LGU", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Tile)
                .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lguUrl))) } }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).background(Teal.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AccountBalance, null, tint = Teal, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lguName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (en) "Official Facebook page" else "Opisyal na Facebook page", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.OpenInNew, null, tint = Teal, modifier = Modifier.size(18.dp))
        }
        Text(
            if (en) "Suspensions, advisories and other official updates" else "Suspensyon, abiso at iba pang opisyal na balita",
            color = Muted, fontSize = 12.sp,
        )
    }
}


// ---------- Accuracy log ("Umulan ba?") ----------
@Composable
private fun AccuracyCard(lang: Lang, resumes: Int) {
    val ctx = LocalContext.current
    val en = lang == Lang.EN
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version, resumes) { AccuracyStore.all(ctx) }
    if (entries.isEmpty()) return
    val now = ZonedDateTime.now(ph.weatherapp.core.engine.MANILA)
    val ask = ph.weatherapp.core.log.AccuracyLog.toAsk(entries, now)
    val scores = ph.weatherapp.core.log.AccuracyLog.scores(entries)
    val answered = entries.count { it.rained != null }
    if (ask == null && answered == 0) return
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardNavy).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FactCheck, null, tint = Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (en) "Forecast check" else "Tsek ng forecast", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (ask != null) {
            val isToday = ask.date == now.toLocalDate()
            val day = if (isToday) (if (en) "today" else "ngayong araw")
                      else ask.date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
            Text(
                if (en) "Did it rain in ${ask.placeName} $day (6 AM–6 PM)?"
                else "Umulan ba sa ${ask.placeName} $day (6 AM–6 PM)?",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                (if (en) "This morning's forecast: " else "Forecast kaninang umaga: ") + "${ask.appRainPct}%" +
                    (ask.pagasaSky?.let { " · PAGASA: $it" } ?: ""),
                color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { AccuracyStore.answer(ctx, ask.date, true); version++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy),
                    modifier = Modifier.weight(1f),
                ) { Text(if (en) "Yes, it rained" else "Oo, umulan", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { AccuracyStore.answer(ctx, ask.date, false); version++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Tile, contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) { Text(if (en) "No" else "Hindi", fontWeight = FontWeight.Bold) }
            }
        }
        if (scores.isNotEmpty()) {
            Text(
                if (en) "Correct rain calls so far ($answered ${if (answered == 1) "day" else "days"})"
                else "Tamang hula ng ulan ($answered araw)",
                color = Muted, fontSize = 12.sp,
            )
            scores.forEach { sc ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sc.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${sc.correct}/${sc.days}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = if (sc.pct >= 70) Teal else if (sc.pct >= 50) Amber else Orange)
                    Text(
                        "  " + (if (en) "${sc.falseAlarms} false alarm, ${sc.misses} missed"
                                else "${sc.falseAlarms} maling babala, ${sc.misses} di nahulaan"),
                        color = Faint, fontSize = 11.sp,
                    )
                }
            }
            Text(
                if (en) "Copy log" else "Kopyahin ang log",
                color = Teal.copy(alpha = 0.85f), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(ph.weatherapp.core.log.AccuracyLog.toJson(AccuracyStore.all(ctx))))
                    Toast.makeText(ctx, if (en) "Log copied" else "Nakopya ang log", Toast.LENGTH_SHORT).show()
                }.padding(vertical = 2.dp),
            )
        }
    }
}

// ---------- Place picker ----------

@Composable
private fun PlacePicker(lang: Lang, onDismiss: () -> Unit, onUseLocation: () -> Unit, onSearch: () -> Unit, onEditProfile: () -> Unit) {
    val en = lang == Lang.EN
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardNavy,
        title = { Text(if (en) "Location" else "Lokasyon", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlaceOption(Icons.Rounded.MyLocation, if (en) "Use my location" else "Gamitin ang aking lokasyon",
                    if (en) "Nearest covered city, or weather for where you are" else "Pinakamalapit na lungsod, o panahon kung nasaan ka",
                    onUseLocation)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                PlaceOption(Icons.Rounded.Search, if (en) "Choose a city or municipality" else "Pumili ng lungsod o bayan",
                    if (en) "All provinces, cities and municipalities" else "Lahat ng probinsya, lungsod at bayan", onSearch)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                PlaceOption(Icons.Rounded.ManageAccounts, if (en) "Edit profile" else "I-edit ang profile",
                    if (en) "Nickname, location and LGU page" else "Palayaw, lokasyon at pahina ng LGU", onEditProfile)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (en) "Close" else "Isara") } },
    )
}

@Composable
private fun PlaceOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
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

/** Official warning (Signal, rainfall warning, thunderstorm advisory): big, full text, can't be missed. */
@Composable
internal fun AlertBanner(rec: Recommendation, lang: Lang, validUntil: ZonedDateTime? = null) {
    val en = lang == Lang.EN
    val c = if (rec.priority == Priority.URGENT) Red else Amber
    val (icon, title) = when (rec.code) {
        "TCWS" -> Icons.Rounded.Cyclone to (if (en) "Tropical cyclone wind signal" else "Babala ng bagyo")
        "FLOOD", "FLOOD_WATCH" -> Icons.Rounded.Flood to (if (en) "Rainfall warning" else "Babala sa malakas na ulan")
        "THUNDER" -> Icons.Rounded.Thunderstorm to (if (en) "Thunderstorm advisory" else "Babala sa kulog at kidlat")
        else -> Icons.Rounded.Warning to (if (en) "Warning" else "Babala")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.copy(alpha = 0.18f))
            .border(1.5.dp, c, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(c, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Navy, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(rec.short(lang), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
            }
        }
        Text(rec.text(lang), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
        Text(
            "PAGASA" + (validUntil?.let {
                (if (en) " · until " else " · hanggang ") + it.format(DateTimeFormatter.ofPattern("h:mm a"))
            } ?: ""),
            color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Shown above everything while an alarm is on but Android won't let it fill the screen. */
@Composable
private fun FullScreenBanner(lang: Lang, resumes: Int) {
    val ctx = LocalContext.current
    if (Build.VERSION.SDK_INT < 34) return
    val off = remember(resumes) {
        AlarmPrefs.all(ctx).any { it.enabled } && !AlarmNotifier.canUseFullScreen(ctx)
    }
    if (!off) return
    val en = lang == Lang.EN
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Amber.copy(alpha = 0.16f))
            .border(1.dp, Amber, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.AlarmOff, null, tint = Amber, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (en) "Full-screen alarm is off" else "Naka-off ang full-screen na alarm",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (en) "Alarms will only show a small pop-up, not the forecast card."
                    else "Maliit na pop-up lang ang lalabas sa alarm, hindi ang buong ulat.",
                    color = Amber, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Navy),
            ) { Text(if (en) "Turn on" else "I-on", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
internal fun HeroCard(f: WindowForecast, lang: Lang, partial: Boolean) {
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
                text = periodLabel(f, partial = partial, en = en),
            )
        }

        // Big rain chance + glyph
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${f.rainChancePct}%", fontSize = 60.sp, fontWeight = FontWeight.Bold, lineHeight = 62.sp)
                Text(rainLabel(f.rainChancePct, en), color = rainColor(f.rainChancePct), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ConfidenceDot(f.confidence, en)
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
internal fun NextCard(f: WindowForecast, lang: Lang, partial: Boolean = false, adviceCount: Int = 1) {
    val en = lang == Lang.EN
    val isDay = f.window.kind == WindowKind.DAY
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
                text = periodLabel(f, partial = partial, en = en),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(skyIcon(f), null, tint = rainColor(f.rainChancePct), modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${f.rainChancePct}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(rainLabel(f.rainChancePct, en), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                ConfidenceDot(f.confidence, en)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${f.tempMinC}–${f.tempMaxC}°C", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${f.windMaxKph} km/h", color = Muted, fontSize = 12.sp)
            }
        }
        Recommender.recommend(f).take(adviceCount).forEach { AdviceRow(it, lang) }
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
        Text(text, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(label, color = Muted, fontSize = 11.sp, maxLines = 1, softWrap = false)
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

private fun periodLabel(f: WindowForecast, partial: Boolean, en: Boolean): String =
    Periods.label(f.window, if (en) Lang.EN else Lang.FIL, partial, java.time.LocalDate.now(ph.weatherapp.core.engine.MANILA))

internal fun greeting(en: Boolean): String = when (java.time.ZonedDateTime.now(ph.weatherapp.core.engine.MANILA).hour) {
    in 0..11 -> if (en) "Good morning" else "Magandang umaga"
    in 12..17 -> if (en) "Good afternoon" else "Magandang hapon"
    else -> if (en) "Good evening" else "Magandang gabi"
}

/** The first official-warning item (Signal, rainfall warning, thunderstorm), if any. */
internal fun urgentWarning(vararg fs: WindowForecast?): Pair<Recommendation, ZonedDateTime?>? =
    fs.filterNotNull().firstNotNullOfOrNull { f ->
        Recommender.recommend(f).firstOrNull { it.code in setOf("TCWS", "FLOOD", "FLOOD_WATCH", "THUNDER") }
            ?.let { it to f.pagasa?.validUntil }
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
