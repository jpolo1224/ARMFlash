package com.armsx.flashpoint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.data.CustomDriver
import com.armsx.flashpoint.data.GpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-time setup's GPU driver offer, ported from ARMSX2's onboarding driver page (ARMSX2
 * team): on a GPU one of the tuned drivers is built for, while the system driver is still in use,
 * offer to download and install it as the global driver. ARMFlash's reason is native Flash: the
 * stock Adreno driver leaks GPU memory in some games until the game crashes.
 */
@Composable
fun SetupDriverOffer() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember { GpuInfo.rendererName() }
    // Decided once: installing must not take the card away.
    val source = remember { CustomDriver.setupOfferLabel(renderer)?.takeIf { CustomDriver.globalId().isBlank() } } ?: return
    var pack by remember { mutableStateOf<CustomDriver.RemoteDriver?>(null) }
    var stage by remember { mutableStateOf("loading") } // loading, unreachable, ready, installing, installed, failed
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(tries) {
        stage = "loading"
        pack = withContext(Dispatchers.IO) { CustomDriver.fetchSetupOffer(renderer) }
        stage = if (pack != null) "ready" else "unreachable"
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GPU driver for Flash games", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Native Flash runs best on a tuned graphics driver. Your phone's own driver can leak graphics memory in some games until they crash.",
            color = Fp.TextDim, fontSize = 13.sp,
        )
        pack?.let { p ->
            Surface(shape = RoundedCornerShape(14.dp), color = Fp.Card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.title.ifBlank { p.assetName }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        listOf(source, p.tagName, if (p.sizeBytes > 0) "%.0f MB".format(p.sizeBytes / 1048576.0) else "").filter(String::isNotBlank).joinToString(" · "),
                        color = Fp.TextDim, fontSize = 12.sp,
                    )
                }
            }
        }
        when (stage) {
            "loading", "installing" -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (stage == "loading") "Fetching…" else "Downloading and installing…", color = Fp.TextDim, fontSize = 13.sp)
            }
            "unreachable" -> {
                Text("Couldn't read the driver list from GitHub. Check your connection and try again.", color = Fp.TextDim, fontSize = 13.sp)
                Chip("Try again", on = false) { tries++ }
            }
            "ready", "failed" -> {
                if (stage == "failed") Text("The download didn't finish. Try again.", color = Color(0xFFFF8A80), fontSize = 13.sp)
                Chip("Download and install", on = true) {
                    val p = pack ?: return@Chip
                    stage = "installing"
                    scope.launch {
                        val d = withContext(Dispatchers.IO) { CustomDriver.download(ctx, p) }
                        if (d != null) CustomDriver.select(d.id)
                        stage = if (d != null) "installed" else "failed"
                    }
                }
            }
            "installed" -> Text("Installed. It takes effect when a game starts.", color = Color(0xFF81C784), fontSize = 13.sp)
        }
        if (CustomDriver.offersMaliSX2(renderer)) Text(
            "On some Mali phones the driver needs a newer Mali kernel driver than the phone has. If yours is too old, ARMFlash keeps using the system driver on its own.",
            color = Fp.TextDim, fontSize = 12.sp,
        )
        Text("You can skip this. The same driver is in Config, under GPU driver.", color = Fp.TextDim, fontSize = 12.sp)
    }
}
