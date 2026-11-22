package com.armsx.flashpoint.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
 * The desktop About tab, plus FlashARM's own section. Contributors come from Flashpoint's
 * credits.json (the file the desktop launcher reads from Data/), bundled in assets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    val credits = remember { Credits.load(ctx) }
    var picked by remember { mutableStateOf<Credits.Profile?>(null) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(Modifier.fillMaxSize().background(Fp.Browse).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("About", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Spacer(Modifier.height(10.dp))
        HomeBox("Flashpoint Archive") {
            Text(
                "The preservation project this launcher was built for, a non-profit with the goal of furthering " +
                    "archival and accessibility efforts of games, animations and other digital interactive experiences on the web.",
                color = Fp.Text, fontSize = 14.sp,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Website", false) { open("https://flashpointarchive.org/") }
                Chip("Discord", false) { open("https://discordapp.com/invite/qhvAkhWXU5") }
            }
        }
        HomeBox("Flashpoint Launcher") {
            Text("An open-source desktop application used to browse, manage and play games from the Flashpoint Archive. FlashARM follows its design and runs its database library.", color = Fp.Text, fontSize = 14.sp)
            Text("License: MIT", color = Fp.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp)) { Chip("GitHub", false) { open("https://github.com/FlashpointProject/launcher") } }
        }
        HomeBox("FlashARM") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.fp_logo), null, Modifier.size(48.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Version ${BuildConfig.VERSION_NAME}", color = Fp.Text, fontWeight = FontWeight.Bold)
                    Text("An unofficial native Android port. Not affiliated with or endorsed by the Flashpoint Archive team.", color = Fp.TextDim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            APP_CREDITS.forEach { (what, who) ->
                Text(what, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Text(who, color = Fp.TextDim, fontSize = 13.sp)
            }
        }
        HomeBox("Credits") {
            if (credits.profiles.isEmpty()) {
                Text("Special Thanks: everyone who has contributed to Flashpoint Archive.", color = Fp.Text)
            }
            credits.blocks().forEach { (role, people) ->
                Text(role.name, color = role.color ?: Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { p ->
                        Column(
                            Modifier.width(76.dp).clickable { picked = if (picked == p) null else p },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(Fp.Card)) {
                                if (p.icon != null) AsyncImage(Credits.model(p.icon), p.title, Modifier.size(52.dp))
                            }
                            Text(p.title, color = Fp.Text, fontSize = 11.sp, maxLines = 2)
                        }
                    }
                }
            }
            picked?.let { p ->
                Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(6.dp)).background(Fp.Field).padding(10.dp)) {
                    Text(p.title, color = Color.White, fontWeight = FontWeight.Black)
                    Text(p.roles.joinToString(", "), color = Fp.Accent, fontSize = 12.sp)
                    p.note?.let { Text(it, color = Fp.Text, fontSize = 13.sp) }
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
        val o = JSONObject(ctx.assets.open("credits.json").bufferedReader().readText())
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
