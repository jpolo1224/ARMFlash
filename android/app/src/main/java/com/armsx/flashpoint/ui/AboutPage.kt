package com.armsx.flashpoint.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.armsx.flashpoint.BuildConfig
import com.armsx.flashpoint.R
import org.json.JSONObject

/**
 * The desktop About tab, laid out as the launcher draws it: the Flashpoint Archive and launcher
 * sections, the quote and the changelog on the left; Credits on the right as round avatars under
 * each role, a tap (or the controller's A) showing who it is. Contributors come from Flashpoint's
 * credits.json: the copy in the Flashpoint folder's Data/ (copied from a desktop install) wins
 * over the one bundled in the app. One column on narrow screens.
 */
@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    val credits = remember { Credits.load(ctx) }
    var picked by remember { mutableStateOf<Credits.Profile?>(null) }
    var releases by remember { mutableStateOf<List<com.armsx.flashpoint.data.News.Item>>(emptyList()) }
    LaunchedEffect(Unit) {
        releases = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.armsx.flashpoint.data.News.fetch(ctx, forceRefresh = false)?.items }.getOrNull().orEmpty()
        }
    }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    BoxWithConstraints(Modifier.fillMaxSize().background(Fp.Browse)) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padVerticalScroll().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("About", color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 34.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            val left: @Composable () -> Unit = {
                AboutSection("Flashpoint Archive") {
                    Text(
                        "The preservation project this launcher was built for, a non-profit with the goal of furthering archival " +
                            "and accessibility efforts of games, animations and other digital interactive experiences on the web.",
                        color = Fp.Text, fontSize = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                        AboutLink("Website") { open("https://flashpointarchive.org/") }
                        AboutLink("Discord") { open("https://discordapp.com/invite/qhvAkhWXU5") }
                    }
                }
                AboutSection("Flashpoint Launcher") {
                    Text("An open-source desktop application used to browse, manage and play games from the Flashpoint Archive. FlashARM follows its design and uses its database library.", color = Fp.Text, fontSize = 14.sp)
                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("License: ") }; append("MIT") }, color = Fp.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    AboutLink("GitHub", Modifier.padding(top = 4.dp)) { open("https://github.com/FlashpointProject/launcher") }
                }
                AboutSection("FlashARM") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.fp_logo), null, Modifier.size(44.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Version ${BuildConfig.VERSION_NAME}", color = Fp.Text, fontWeight = FontWeight.Bold)
                            Text("An unofficial native Android port. Not affiliated with or endorsed by the Flashpoint Archive team.", color = Fp.TextDim, fontSize = 13.sp)
                        }
                    }
                    APP_CREDITS.forEach { (what, who) ->
                        Text(what, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(who, color = Fp.TextDim, fontSize = 13.sp)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\u201CIt's not up to us to decide what the future finds interesting\u201D", color = Fp.Text, fontStyle = FontStyle.Italic, fontSize = 14.sp)
                    Text("-Jason Scott", color = Fp.Text, fontSize = 13.sp)
                }
                AboutSection("Changelog") {
                    if (releases.isEmpty()) Text("Release notes appear here once FlashARM has a GitHub release.", color = Fp.TextDim, fontSize = 13.sp)
                    releases.forEach { r ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).padFocus(RoundedCornerShape(3.dp))
                                .clip(RoundedCornerShape(3.dp)).background(Fp.Panel)
                                .border(1.dp, Color(0xFF55555B), RoundedCornerShape(3.dp))
                                .clickable { com.armsx.flashpoint.data.News.releaseUrl(r.tag).takeIf { it.isNotEmpty() }?.let { open(it) } }.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("Date: ") }; append(r.published.take(10)) }, color = Fp.Text, fontSize = 14.sp)
                            Text("FlashARM ${r.title.ifBlank { r.tag }}", color = Fp.Text, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }
            }
            val right: @Composable () -> Unit = {
                CreditsPanel(credits, picked) { picked = if (picked == it) null else it }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.1f)) { left() }
                    Column(Modifier.weight(1f)) { right() }
                }
            } else {
                left()
                Spacer(Modifier.height(12.dp))
                right()
            }
        }
    }
}

/** A desktop About section: the title on a dark bar fading out to the right, the content under it. */
@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF111112), Color(0xFF111112), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(title, color = Fp.Strong, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.padding(start = 10.dp, top = 4.dp)) { content() }
    }
}

@Composable
private fun AboutLink(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = Fp.Text, fontSize = 15.sp, textDecoration = TextDecoration.Underline,
        modifier = modifier.padFocus(RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp),
    )
}

/** The desktop Credits column: each role's name in its colour, then its people as round avatars. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditsPanel(credits: Credits.Data, picked: Credits.Profile?, onPick: (Credits.Profile) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Credits", color = Fp.Strong, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        if (credits.profiles.isEmpty()) {
            Text("Special Thanks: everyone who has contributed to Flashpoint Archive.", color = Fp.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Copy credits.json from a desktop Flashpoint's Data folder into ${"\u0022"}Flashpoint/Data${"\u0022"} here to list every contributor.", color = Fp.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        credits.blocks().forEach { (role, people) ->
            Text(role.name, color = role.color ?: Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                people.forEach { p ->
                    Box(
                        Modifier.size(52.dp).padFocus(CircleShape).clip(CircleShape).background(Fp.Card).clickable { onPick(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (p.icon != null) AsyncImage(Credits.model(p.icon), p.title, Modifier.fillMaxSize().clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Text(p.title.take(1), color = Fp.Text, fontWeight = FontWeight.Black)
                    }
                }
            }
            // Who was picked, under their role, as the desktop's tooltip shows it.
            if (picked != null && people.contains(picked)) {
                Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(Fp.Field).padding(10.dp)) {
                    Text(picked.title, color = Fp.Strong, fontWeight = FontWeight.Black)
                    Text(picked.roles.joinToString(", "), color = role.color ?: Fp.Accent, fontSize = 12.sp)
                    picked.note?.let { Text(it, color = Fp.Text, fontSize = 13.sp) }
                }
            }
        }
    }
}

private val APP_CREDITS = listOf(
    "App icon" to "Flashpoint logo by the Flashpoint Archive creators; this version by u/Alarming-Brick-3670.",
    "flashpoint-archive (FPA-Rust)" to "Flashpoint's database library. MIT License, FlashpointProject.",
    "Flash" to "Ruffle, the Flash Player emulator. MIT or Apache-2.0, ruffle.rs.",
    "UI music" to "“Awestruck”.",
    "Pause menu music" to "“Underwater Ambient Pad” by isaiah658.",
    "Menu sounds" to "Interface SFX Pack 1 by obsydianx (CC0), trimmed by the ARMSX2 team.",
    "From ARMSX2" to "Touch control sprites, the in-game menu, battery indicator, custom driver manager, Discord presence and controller focus ring, by the ARMSX2 team.",
    "Custom drivers" to "libadrenotools by Billy Laws (BSD 2-Clause).",
    "Cursors" to "Modern Mac: Capitaine Cursors by Keefer Rourke and others (LGPL-3.0). Linux: DMZ-White by Novell (CC BY-SA 3.0).",
    "Windows players" to "Box64 by ptitSeb and contributors (MIT); Wine (LGPL-2.1); Winlator's base system by brunodev85 (LGPL-2.1); Xvfb from X.Org (MIT) as built by Termux; players from Flashpoint's support packs.",
    "Native libraries" to "tokio, hyper, rustls, ring, zip, rusqlite with SQLite (public domain) and other crates under MIT or Apache-2.0.",
)

/** Flashpoint's credits.json, parsed like the launcher's CreditsFile.ts. */
object Credits {
    data class Role(val name: String, val color: Color?, val noCategory: Boolean)
    data class Profile(val title: String, val roles: List<String>, val note: String?, val icon: String?, val topRole: String?)
    data class Data(val roles: List<Role>, val profiles: List<Profile>) {
        /** Each profile under the first of its roles that is a category (or its topRole), in role order. */
        fun blocks(): List<Pair<Role, List<Profile>>> {
            val cats = roles.filter { !it.noCategory }
            val special = Role("Special Thanks", null, false)
            val byRole = LinkedHashMap<Role, MutableList<Profile>>()
            cats.forEach { byRole[it] = mutableListOf() }
            profiles.forEach { p ->
                val wanted = p.topRole?.let { listOf(it) } ?: p.roles
                val role = wanted.firstNotNullOfOrNull { n -> cats.firstOrNull { it.name == n } } ?: special
                byRole.getOrPut(role) { mutableListOf() }.add(p)
            }
            return byRole.filterValues { it.isNotEmpty() }.toList()
        }
    }

    fun load(ctx: android.content.Context): Data = runCatching {
        val local = java.io.File((ctx.applicationContext as com.armsx.flashpoint.FpApp).library.root, "Data/credits.json")
        val text = if (local.isFile) local.readText() else ctx.assets.open("credits.json").bufferedReader().readText()
        val o = JSONObject(text)
        val roles = o.optJSONArray("roles")?.let { a ->
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                Role(r.optString("name"), parseColor(r.optString("color")), r.optBoolean("noCategory"))
            }
        }.orEmpty()
        val profiles = o.optJSONArray("profiles")?.let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val rs = p.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty()
                Profile(
                    p.optString("title"), rs,
                    p.optString("note").takeIf { it.isNotBlank() },
                    p.optString("icon").takeIf { it.isNotBlank() },
                    p.optString("topRole").takeIf { it.isNotBlank() },
                )
            }
        }.orEmpty()
        Data(roles, profiles)
    }.getOrDefault(Data(emptyList(), emptyList()))

    /** credits.json icons are URLs, local paths, or data: URIs; the image loader takes bytes for the last. */
    fun model(icon: String): Any = if (icon.startsWith("data:")) {
        runCatching { android.util.Base64.decode(icon.substringAfter(","), android.util.Base64.DEFAULT) }.getOrDefault(icon)
    } else icon

    private fun parseColor(s: String): Color? = runCatching { Color(android.graphics.Color.parseColor(s.trim())) }.getOrNull()
}
