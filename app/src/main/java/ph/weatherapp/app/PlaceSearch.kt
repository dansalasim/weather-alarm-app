package ph.weatherapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ph.weatherapp.core.engine.Lang

/**
 * Full-screen picker for any Philippine city or municipality:
 * type to search, or browse by province.
 */
@Composable
fun PlaceSearchDialog(lang: Lang, onDismiss: () -> Unit, onPick: (PhPlace) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val en = lang == Lang.EN
    var query by remember { mutableStateOf("") }
    var province by remember { mutableStateOf<String?>(null) }
    val groups = remember { PlaceDirectory.groups(ctx) }
    val results = remember(query) { PlaceDirectory.search(ctx, query) }
    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Search bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (province != null && query.isBlank()) province = null else onDismiss() }) {
                        Icon(Icons.Rounded.ArrowBack, if (en) "Back" else "Bumalik", tint = Color.White)
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (en) "Search city or municipality" else "Hanapin ang lungsod o bayan") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null, tint = Muted) }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal, unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                            cursorColor = Teal, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(8.dp))

                when {
                    groups.isEmpty() -> Text(
                        if (en) "The place list isn't available in this version." else "Wala ang listahan ng lugar sa bersyong ito.",
                        color = Muted, modifier = Modifier.padding(16.dp),
                    )
                    query.isNotBlank() -> {
                        if (results.isEmpty()) {
                            Text(if (en) "No match for \"$query\"" else "Walang tugma sa \"$query\"",
                                color = Muted, modifier = Modifier.padding(16.dp))
                        }
                        LazyColumn { items(results, key = { it.code }) { p -> PlaceRow(p, en, showArea = true) { onPick(p) } } }
                    }
                    province != null -> {
                        val list = groups.firstOrNull { it.first == province }?.second.orEmpty()
                        Text(province!!, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                        Text((if (en) "${list.size} cities and municipalities" else "${list.size} lungsod at bayan"),
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                        LazyColumn { items(list, key = { it.code }) { p -> PlaceRow(p, en, showArea = false) { onPick(p) } } }
                    }
                    else -> {
                        Text(if (en) "Browse by province" else "Pumili ayon sa probinsya",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                        LazyColumn {
                            items(groups, key = { it.first }) { (name, list) ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { province = name }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Map, null, tint = Teal, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${list.size}", color = Muted, fontSize = 12.sp)
                                    Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: PhPlace, en: Boolean, showArea: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(Teal.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (p.isCity) Icons.Rounded.LocationCity else Icons.Rounded.Place, null, tint = Teal, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (showArea) add(p.areaLabel)
                add(if (p.isCity) (if (en) "City" else "Lungsod") else (if (en) "Municipality" else "Bayan"))
            }.joinToString(" · ")
            Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.covered) {
            Text("PAGASA+", color = Navy, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Teal, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}
