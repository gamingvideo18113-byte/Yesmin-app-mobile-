package com.yesmin.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YesminTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        Bus.resume.value++
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val st by Bus.state.collectAsState()
    val active = st != VState.IDLE && st != VState.ERROR
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceService.start(ctx) else tab = 3
    }
    Column(Modifier.fillMaxSize().background(BG)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeScreen()
                1 -> ChatScreen()
                2 -> HistoryScreen()
                else -> SettingsScreen()
            }
        }
        BottomBar(tab, { tab = it }, active) {
            if (active) VoiceService.stop(ctx)
            else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) VoiceService.start(ctx)
            else mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
