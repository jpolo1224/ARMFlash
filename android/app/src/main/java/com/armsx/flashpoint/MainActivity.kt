package com.armsx.flashpoint

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx.flashpoint.game.GameActivity
import com.armsx.flashpoint.ui.BrowseScreen
import com.armsx.flashpoint.ui.BrowseVm
import com.armsx.flashpoint.ui.Chip
import com.armsx.flashpoint.ui.Fp
import com.armsx.flashpoint.ui.FpTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: BrowseVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FpTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: BrowseVm) {
    var showConfig by remember { mutableStateOf(false) }
    val pickDb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDatabase(uri)
    }
    val playLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val id = r.data?.getStringExtra(GameActivity.EXTRA_GAME_ID)
            val secs = r.data?.getLongExtra(GameActivity.EXTRA_SECONDS, 0) ?: 0
            if (id != null && secs > 0) vm.addPlaytime(id, secs)
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Box(Modifier.fillMaxSize().background(Fp.Header).safeDrawingPadding()) {
        when {
            showConfig -> {
                BackHandler { showConfig = false }
                Config(vm, onImport = { pickDb.launch(arrayOf("*/*")) }, onClose = { showConfig = false })
            }
            !vm.dbReady -> Setup(vm, onImport = { pickDb.launch(arrayOf("*/*")) })
            else -> {
                BackHandler(enabled = vm.selected != null) { vm.clearSelection() }
                BrowseScreen(
                    vm,
                    onPlay = { game ->
                        vm.prepare(game) { ready ->
                            playLauncher.launch(
                                GameActivity.intent(
                                    ctx, ready.game.id, ready.plan.runner, ready.plan.url, ready.zips,
                                    vm.library.htdocs.path, vm.library.legacyBase,
                                ),
                            )
                        }
                    },
                    onConfig = { showConfig = true },
                )
            }
        }
        if (vm.status.isNotBlank()) {
            LaunchedEffect(vm.status) { delay(4000); vm.status = "" }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xEE111111)).padding(12.dp),
            ) { Text(vm.status, color = Color.White) }
        }
    }
}

@Composable
private fun Setup(vm: BrowseVm, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Fp.Browse).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Flashpoint", color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pick the flashpoint.sqlite from a Flashpoint install (Data/flashpoint.sqlite). " +
                "Games download on demand the first time you play them.",
            color = Fp.Text, modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(20.dp))
        val imp = vm.importing
        if (imp != null) {
            Text("Importing: ${imp / (1 shl 20)} MB", color = Fp.Text)
        } else {
            Chip("Import flashpoint.sqlite", on = true, onClick = onImport)
        }
    }
}

@Composable
private fun Config(vm: BrowseVm, onImport: () -> Unit, onClose: () -> Unit) {
    val lib = vm.library
    var source by remember { mutableStateOf(lib.gameDataSource) }
    var images by remember { mutableStateOf(lib.imagesBase) }
    var legacy by remember { mutableStateOf(lib.legacyOnline) }
    Column(Modifier.fillMaxSize().background(Fp.Browse).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Config", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(source, { source = it; lib.gameDataSource = it }, label = { Text("Game data source") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(images, { images = it; lib.imagesBase = it }, label = { Text("Images server") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(legacy, { legacy = it; lib.legacyOnline = it })
            Spacer(Modifier.padding(6.dp))
            Text("Fetch Legacy game files from Flashpoint's server", color = Fp.Text)
        }
        Spacer(Modifier.height(16.dp))
        Text("Flashpoint folder: ${lib.root.path}", color = Fp.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Chip("Import a different flashpoint.sqlite", false, onImport)
        Spacer(Modifier.height(10.dp))
        Chip("Back", false, onClose)
    }
}
