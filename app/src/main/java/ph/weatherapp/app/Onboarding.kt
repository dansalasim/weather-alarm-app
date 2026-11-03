package ph.weatherapp.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ph.weatherapp.core.area.Areas

// ---------------------------------------------------------------------------
// Landing page with the disclaimer (shown when the app is opened normally)
// ---------------------------------------------------------------------------

@Composable
fun LandingScreen(onUnderstand: () -> Unit, onDontShowAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            AppLogo(88.dp)
            Spacer(Modifier.height(14.dp))
            Text("Weather Alarm PH", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Your weather, your alarm, your city", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardNavy)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = Amber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disclaimer", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Para("Weather Alarm PH gives forecasts, which are predictions and not guarantees. Weather can change quickly, so actual conditions in your area may differ from what the app shows.")
                Para("We use data from established weather authorities: PAGASA, ECMWF, and GFS. When they disagree, PAGASA's data prevails. Weather Alarm PH is an independent app and is not affiliated with or endorsed by PAGASA or any government agency.")
                Para("Recommendations such as \"bring an umbrella\" are general tips, not professional advice. Suspension notices come from official LGU announcements, but always confirm them with your LGU, school, or employer. Alarms may not ring if your phone is off, silenced, or restricting the app.")
                Text(
                    buildAnnotatedString {
                        append("This app does not replace official warnings. During typhoons, floods, and other emergencies, follow PAGASA, NDRRMC, and your local DRRM office. In an emergency, call ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Amber)) { append("911") }
                        append(".")
                    },
                    fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.88f),
                )
                Para("Weather Alarm PH is provided \"as is.\" You use it at your own risk, and the developer is not liable for any loss or inconvenience caused by relying on it.")
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = onUnderstand,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy),
        ) { Text("I understand", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onDontShowAgain, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Don't show again", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.88f))
}

@Composable
fun AppLogo(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(0xFF0A1628))
            .border(1.dp, Teal.copy(alpha = 0.25f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------------------
// First-launch setup: nickname, location, LGU Facebook page (also used to edit later)
// ---------------------------------------------------------------------------

@Composable
fun SetupScreen(editing: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()

    var nickname by remember { mutableStateOf(Profile.nickname(app) ?: "") }
    var place by remember { mutableStateOf(if (editing || Profile.isSetUp(app)) PlacePrefs.get(app) else null) }
    var usingLocation by remember { mutableStateOf(editing && PlacePrefs.isAuto(app) && Locator.hasPermission(app)) }
    var locating by remember { mutableStateOf(false) }
    var locationNote by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var lguName by remember { mutableStateOf(Profile.savedLguName(app)) }
    var lguUrl by remember { mutableStateOf(Profile.savedLguUrl(app)) }
    var lguEdited by remember { mutableStateOf(lguUrl.isNotBlank()) }

    // Pre-fill the LGU unless the user typed their own: the built-in page for Isabela and
    // Zamboanga; elsewhere just the name ("Municipal Government of Maluso"), so only the link is left.
    fun prefillLgu(p: Place?) {
        if (lguEdited) return
        val a = Areas.byId(p?.areaId)
        if (a != null) {
            lguName = a.lguName
            lguUrl = a.lguPageUrl ?: ""
            return
        }
        val town = PlaceDirectory.byCode(app, p?.code)
        lguName = when {
            town == null -> ""
            town.isCity -> "City Government of " + town.official.removePrefix("City of ").removeSuffix(" City")
            else -> "Municipal Government of " + town.official
        }
        lguUrl = ""
    }

    fun detect() {
        locating = true
        locationNote = null
        PlacePrefs.useLocation(app)
        scope.launch {
            Locator.refreshPlace(app)
            val p = PlacePrefs.get(app)
            place = p
            usingLocation = true
            locating = false
            if (p.areaId == null) locationNote = "Class and work suspension alerts are available for Isabela City and Zamboanga City so far. You'll still get the full forecast, including PAGASA, for ${p.name}."
            prefillLgu(p)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) detect() else locationNote = "Location not allowed. Pick your city below instead."
    }

    if (showSearch) PlaceSearchDialog(ph.weatherapp.core.engine.Lang.EN, onDismiss = { showSearch = false }) { picked ->
        showSearch = false
        PlacePrefs.pickPlace(app, picked)
        place = PlacePrefs.get(app)
        usingLocation = false
        locationNote = null
        prefillLgu(place)
    }

    val urlValid = lguUrl.isBlank() || Profile.normalizeFacebookUrl(lguUrl) != null
    val canFinish = nickname.isNotBlank() && place != null && urlValid && !locating

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Teal, unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
        focusedLabelColor = Teal, unfocusedLabelColor = Muted, cursorColor = Teal,
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    )

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                AppLogo(48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (editing) "Your profile" else "Welcome!", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (editing) "Change your details anytime" else "Let's set up Weather Alarm PH", color = Muted, fontSize = 13.sp)
                }
            }

            // 1. Nickname
            Section(Icons.Rounded.Person, "What should we call you?") {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(24) },
                    label = { Text("Nickname") },
                    placeholder = { Text("e.g. Dan") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 2. Location
            Section(Icons.Rounded.LocationOn, "Where are you?") {
                Choice(
                    icon = Icons.Rounded.MyLocation,
                    title = "Use my location",
                    subtitle = when {
                        locating -> "Finding your location…"
                        usingLocation && place != null -> "📍 " + place!!.name
                        else -> "Nearest covered city, or weather for where you are"
                    },
                    selected = usingLocation,
                ) {
                    if (Locator.hasPermission(app)) detect() else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                Choice(
                    icon = Icons.Rounded.Search,
                    title = "Choose a city or municipality",
                    subtitle = if (!usingLocation && place != null) "📍 " + place!!.name else "All provinces, cities and municipalities",
                    selected = !usingLocation && place != null,
                ) { showSearch = true }
                locationNote?.let { Text(it, color = Amber, fontSize = 12.sp, lineHeight = 16.sp) }
            }

            // 3. LGU page
            Section(Icons.Rounded.AccountBalance, "Your LGU's official Facebook page") {
                Text("Shown as \"LGU announcements\" so you can check suspensions and advisories quickly. Optional.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                OutlinedTextField(
                    value = lguName,
                    onValueChange = { lguName = it.take(60); lguEdited = true },
                    label = { Text("LGU name") },
                    placeholder = { Text("e.g. City Government of Isabela") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lguUrl,
                    onValueChange = { lguUrl = it.trim(); lguEdited = true },
                    label = { Text("Facebook page link") },
                    placeholder = { Text("facebook.com/yourlgu") },
                    singleLine = true,
                    isError = !urlValid,
                    supportingText = { if (!urlValid) Text("Paste a facebook.com link") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                Profile.save(app, nickname, lguName, Profile.normalizeFacebookUrl(lguUrl))
                onDone()
            },
            enabled = canFinish,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Navy),
        ) {
            Text(if (editing) "Save" else "Get started", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (!canFinish && !locating) {
            Text(
                when {
                    nickname.isBlank() -> "Enter a nickname to continue"
                    place == null -> "Choose your location to continue"
                    else -> "Check the Facebook link"
                },
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Section(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardNavy).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun Choice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Teal.copy(alpha = 0.14f) else Tile)
            .border(1.dp, if (selected) Teal else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(Teal.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Teal, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Teal, modifier = Modifier.size(20.dp))
    }
}
