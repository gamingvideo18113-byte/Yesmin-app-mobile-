package com.yesmin.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------------ HOME
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val st by Bus.state.collectAsState()
    val status by Bus.status.collectAsState()
    val muted by Bus.muted.collectAsState()
    val tr by Bus.transcript.collectAsState()
    val resume by Bus.resume.collectAsState()
    val active = st != VState.IDLE && st != VState.ERROR
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Prefs.assistantName, fontSize = 28.sp, color = androidx.compose.ui.graphics.Color.White)
        Text(when {
            muted && active -> "Mic mute hai"
            st == VState.IDLE -> "Neeche wale orb ko dabayein, main sunna shuru karti hoon"
            else -> status
        }, color = DIM, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Orb(260.dp)
        Spacer(Modifier.height(10.dp))
        Text(when (st) {
            VState.LISTENING -> "${Prefs.assistantName} sun rahi hai..."
            VState.THINKING -> "${Prefs.assistantName} kaam kar rahi hai..."
            VState.SPEAKING -> "${Prefs.assistantName} bol rahi hai..."
            VState.CONNECTING -> "Connect ho rahi hai..."
            VState.ERROR -> "Dikkat aayi"
            VState.IDLE -> "Ready"
        }, color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        if (active) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { VoiceService.mute(ctx) }) { Text(if (muted) "Unmute" else "Mute") }
            Button(onClick = { VoiceService.stop(ctx) }) { Text("End") }
        }
        Spacer(Modifier.height(10.dp))
        val a11y = YesminAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(ctx)
        val key = Prefs.apiKey.isNotBlank()
        resume.let { Text("API key ${if (key) "✓" else "✗"}   Accessibility ${if (a11y) "✓" else "✗"}   Overlay ${if (overlay) "✓" else "✗"}", color = DIM, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(tr.takeLast(8)) { Text(it, color = DIM, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

// ------------------------------------------------------------------ CHAT
@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Pair<String, String>>() }
    val history = remember { JSONArray() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(msgs) { (who, text) ->
                val me = who == "me"
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (me) PURPLE else CARD).padding(12.dp)) {
                        Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp)
                    }
                }
            }
            if (busy) item { Text("${Prefs.assistantName} soch rahi hai...", color = DIM, fontSize = 13.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message likho...") })
            Spacer(Modifier.size(8.dp))
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                val t = input.trim(); input = ""; busy = true
                msgs.add("me" to t)
                history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t))))
                scope.launch {
                    val r = try { ChatEngine.reply(history) } catch (e: Exception) { "Error: ${e.message}" }
                    msgs.add("ai" to r); busy = false
                }
            }) { Text("Bhejo") }
        }
    }
}

// ------------------------------------------------------------------ HISTORY
@Composable
fun HistoryScreen() {
    val log by Bus.log.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Action log", fontSize = 20.sp, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { Bus.log.value = emptyList() }) { Text("Saaf karo") }
        }
        if (log.isEmpty()) Text("Abhi kuch nahi hua. Yesmin jo bhi karegi yahan dikhega.", color = DIM)
        LazyColumn { items(log) { Text(it, color = DIM, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp)) } }
    }
}

// ------------------------------------------------------------------ SETTINGS
data class VoiceInfo(val name: String, val trait: String)

val VOICES = listOf(
    "Aoede" to "Breezy", "Kore" to "Firm", "Zephyr" to "Bright", "Leda" to "Youthful", "Callirrhoe" to "Easy-going",
    "Autonoe" to "Bright", "Despina" to "Smooth", "Erinome" to "Clear", "Achernar" to "Soft", "Gacrux" to "Mature",
    "Laomedeia" to "Upbeat", "Pulcherrima" to "Forward", "Sulafat" to "Warm", "Vindemiatrix" to "Gentle", "Achird" to "Friendly",
    "Sadachbia" to "Lively", "Puck" to "Upbeat", "Charon" to "Informative", "Fenrir" to "Excitable", "Orus" to "Firm",
    "Enceladus" to "Breathy", "Iapetus" to "Clear", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Algenib" to "Gravelly",
    "Rasalgethi" to "Informative", "Alnilam" to "Firm", "Schedar" to "Even", "Zubenelgenubi" to "Casual", "Sadaltager" to "Knowledgeable"
).map { VoiceInfo(it.first, it.second) }

@Composable
fun H(text: String) = Text(text, fontSize = 16.sp, color = PURPLE, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))

@Composable
fun Row2(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
fun SettingsScreen() {
    var page by remember { mutableStateOf("main") }
    if (page == "perms") { PermissionsScreen { page = "main" }; return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var keyMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var liveModel by remember { mutableStateOf(Prefs.liveModel) }
    var chatModel by remember { mutableStateOf(Prefs.chatModel) }
    var ttsModel by remember { mutableStateOf(Prefs.ttsModel) }
    var modelMsg by remember { mutableStateOf("") }
    var aName by remember { mutableStateOf(Prefs.assistantName) }
    var uName by remember { mutableStateOf(Prefs.userName) }
    var personality by remember { mutableStateOf(Prefs.personality) }
    var style by remember { mutableStateOf(Prefs.style) }
    var lang by remember { mutableStateOf(Prefs.language) }
    var emotion by remember { mutableStateOf(Prefs.emotion) }
    var glow by remember { mutableStateOf(Prefs.edgeGlow) }
    var confirm by remember { mutableStateOf(Prefs.confirmRisky) }
    var dry by remember { mutableStateOf(Prefs.dryRun) }
    var voice by remember { mutableStateOf(Prefs.voice) }
    var showVoices by remember { mutableStateOf(false) }
    var updUrl by remember { mutableStateOf(Prefs.updateUrl) }
    var updMsg by remember { mutableStateOf("") }
    var updInfo by remember { mutableStateOf<Updater.Info?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", fontSize = 24.sp, color = androidx.compose.ui.graphics.Color.White)

        H("Connections (Gemini API key)")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gemini API key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clip.getText()?.text?.let { key = Prefs.cleanKey(it) } }) { Text("Paste") }
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Chhupao" else "Dikhao") }
        }
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                Prefs.apiKey = key
                key = Prefs.apiKey
                if (Prefs.apiKey.isEmpty() || Prefs.apiKey != key) { keyMsg = "Save nahi hua. Key khali ya galat hai."; busy = false; return@launch }
                keyMsg = "Saved. Key check ho rahi hai..."
                Gemini.testKey(Prefs.apiKey).fold(
                    { n -> keyMsg = "✓ Saved aur key sahi hai ($n models mile)"; Bus.resume.value++ },
                    { e -> keyMsg = "Saved, par key check fail: ${e.message}" })
                busy = false
            }
        }) { Text(if (busy) "Check ho raha hai..." else "Save + Test") }
        if (keyMsg.isNotEmpty()) Text(keyMsg, color = if (keyMsg.startsWith("✓")) TEAL else YEL, fontSize = 13.sp)
        Text("Free key: aistudio.google.com/apikey", color = DIM, fontSize = 12.sp)

        H("Models")
        OutlinedTextField(liveModel, { liveModel = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Live (voice) model, khali = auto") })
        OutlinedTextField(chatModel, { chatModel = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Chat model") })
        OutlinedTextField(ttsModel, { ttsModel = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Voice preview (TTS) model, khali = auto") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                Prefs.liveModel = liveModel; Prefs.chatModel = chatModel; Prefs.ttsModel = ttsModel
                scope.launch {
                    modelMsg = "Saved. Auto-detect chal raha hai..."
                    modelMsg = try {
                        val l = Gemini.pickLiveModel(Prefs.apiKey); val t = Gemini.pickTtsModel(Prefs.apiKey)
                        "Saved. Auto live: ${l ?: "nahi mila"}  |  TTS: ${t ?: "nahi mila"}"
                    } catch (e: Exception) { "Saved. Detect fail: ${e.message}" }
                }
            }) { Text("Save + Detect") }
        }
        if (modelMsg.isNotEmpty()) Text(modelMsg, color = DIM, fontSize = 12.sp)

        H("Voice & language")
        Row2("Awaaz: $voice") { Button(onClick = { showVoices = true }) { Text("Chunein") } }
        Text("Language", color = DIM, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Auto", "Hindi", "English", "Hinglish").forEach { Chip(it, lang == it) { lang = it; Prefs.language = it } }
        }
        Text("Personality", color = DIM, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Friendly", "Calm", "Energetic", "Professional", "Funny").forEach { Chip(it, personality == it) { personality = it; Prefs.personality = it } }
        }
        Text("Response style", color = DIM, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Short", "Balanced", "Detailed").forEach { Chip(it, style == it) { style = it; Prefs.style = it } }
        }
        Text("Emotion: ${(emotion * 100).toInt()}%", color = DIM, fontSize = 12.sp)
        Slider(emotion, { emotion = it }, onValueChangeFinished = { Prefs.emotion = emotion })
        OutlinedTextField(aName, { aName = it; Prefs.assistantName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Assistant ka naam") })
        OutlinedTextField(uName, { uName = it; Prefs.userName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Aapka naam") })
        Text("Voice/personality badalne ke baad mic band karke dobara chalu karein.", color = DIM, fontSize = 12.sp)

        H("Behaviour")
        Row2("Screen ke kinare ki glow line") { Switch(glow, { glow = it; Prefs.edgeGlow = it }) }
        Row2("Risky kaam se pehle poochho (call/SMS)") { Switch(confirm, { confirm = it; Prefs.confirmRisky = it }) }
        Row2("Dry-run (sirf batao, karo mat)") { Switch(dry, { dry = it; Prefs.dryRun = it }) }

        H("Permissions")
        Button(onClick = { page = "perms" }) { Text("Permission Center kholo") }

        H("Memory")
        Text("Yaad hai: ${Memory.all().size} baatein", color = DIM)
        TextButton(onClick = { Prefs.memory = "[]" }) { Text("Saari yaaddaasht mitao") }

        H("Updates")
        OutlinedTextField(updUrl, { updUrl = it; Prefs.updateUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("GitHub releases/latest API URL") },
            placeholder = { Text("https://api.github.com/repos/NAAM/REPO/releases/latest") })
        Button(onClick = {
            scope.launch {
                updMsg = "Check ho raha hai..."
                Updater.check(ctx).fold(
                    { i -> updInfo = i; updMsg = if (i == null) "Aap latest version par hain (ya URL khali hai)" else "Naya version ${i.version} mila" },
                    { e -> updMsg = "Check fail: ${e.message}" })
            }
        }) { Text("Update check karo") }
        updInfo?.let { i -> Button(onClick = { Updater.install(ctx, i.url) }) { Text("Download + Install ${i.version}") } }
        if (updMsg.isNotEmpty()) Text(updMsg, color = DIM, fontSize = 12.sp)
        Text("Version ${try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }}", color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(40.dp))
    }

    if (showVoices) VoiceDialog(voice, { showVoices = false }) { voice = it; Prefs.voice = it; showVoices = false }
}

@Composable
fun VoiceDialog(current: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(current) }
    var playing by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, containerColor = CARD,
        title = { Text("Voice", color = androidx.compose.ui.graphics.Color.White) },
        text = {
            Column {
                Text("▶ dabakar suno, phir chunein", color = DIM, fontSize = 12.sp)
                if (msg.isNotEmpty()) Text(msg, color = YEL, fontSize = 12.sp)
                LazyColumn(Modifier.height(340.dp)) {
                    items(VOICES) { v ->
                        Row(Modifier.fillMaxWidth().clickable { sel = v.name }.background(if (sel == v.name) androidx.compose.ui.graphics.Color(0xFF3A2370) else androidx.compose.ui.graphics.Color.Transparent).padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF3A2370)).clickable {
                                scope.launch {
                                    playing = v.name; msg = "${v.name} bol rahi hai..."
                                    try {
                                        val key = Prefs.apiKey
                                        val m = Prefs.ttsModel.ifBlank { Gemini.pickTtsModel(key) } ?: throw IllegalStateException("TTS model nahi mila")
                                        val who = Prefs.assistantName
                                        val pcm = Gemini.tts(key, m, v.name, "Namaste, main $who hoon. Bataiye, main aapki kaise madad kar sakti hoon?")
                                        Pcm.playOnce(pcm); msg = ""
                                    } catch (e: Exception) { msg = "Preview fail: ${e.message}" }
                                    playing = ""
                                }
                            }, contentAlignment = Alignment.Center) { Text(if (playing == v.name) "…" else "▶", color = androidx.compose.ui.graphics.Color.White) }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(v.name, color = androidx.compose.ui.graphics.Color.White)
                                Text(v.trait, color = DIM, fontSize = 12.sp)
                            }
                            if (sel == v.name) Text("✓", color = PURPLE, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(sel) }) { Text("Use $sel") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// ------------------------------------------------------------------ PERMISSIONS
class PermItem(val name: String, val why: String, val granted: (Context) -> Boolean, val request: (Context, (Array<String>) -> Unit) -> Unit)

private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")
private fun go(c: Context, i: Intent) { try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
private fun rt(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

fun permItems(): List<PermItem> = listOf(
    PermItem("Microphone", "Aapki awaaz sunne ke liye (zaroori)", { rt(it, Manifest.permission.RECORD_AUDIO) },
        { _, ask -> ask(arrayOf(Manifest.permission.RECORD_AUDIO)) }),
    PermItem("Notifications", "Mic chalu hone ka notification (Mute/Stop ke saath)",
        { Build.VERSION.SDK_INT < 33 || rt(it, Manifest.permission.POST_NOTIFICATIONS) },
        { _, ask -> if (Build.VERSION.SDK_INT >= 33) ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }),
    PermItem("Accessibility", "Screen padhne, tap, scroll, type karne ke liye (phone control ka dil)",
        { YesminAccessibilityService.instance != null },
        { c, _ -> go(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
    PermItem("Display over other apps", "Dusre apps ke upar glow line dikhane aur background se apps kholne ke liye",
        { Settings.canDrawOverlays(it) },
        { c, _ -> go(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))) }),
    PermItem("Battery: Unrestricted", "Taaki Android mic ko 24 ghante chalne de",
        { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
        { c, _ -> go(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c))) }),
    PermItem("Contacts", "Naam se call/SMS ke liye", { rt(it, Manifest.permission.READ_CONTACTS) },
        { _, ask -> ask(arrayOf(Manifest.permission.READ_CONTACTS)) }),
    PermItem("Phone", "Call lagane, uthane aur kaatne ke liye",
        { rt(it, Manifest.permission.CALL_PHONE) && rt(it, Manifest.permission.ANSWER_PHONE_CALLS) },
        { _, ask -> ask(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)) }),
    PermItem("SMS", "SMS bhejne ke liye", { rt(it, Manifest.permission.SEND_SMS) },
        { _, ask -> ask(arrayOf(Manifest.permission.SEND_SMS)) }),
    PermItem("Modify system settings", "Brightness badalne ke liye", { Settings.System.canWrite(it) },
        { c, _ -> go(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(c))) }),
    PermItem("Install unknown apps", "Auto-update ka APK install karne ke liye", { it.packageManager.canRequestPackageInstalls() },
        { c, _ -> go(c, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(c))) })
)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val resume by Bus.resume.collectAsState()
    val items = remember { permItems() }
    var wizard by remember { mutableStateOf<List<PermItem>?>(null) }
    var step by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        Bus.resume.value++
        if (res.values.any { !it }) {
            // do baar deny ho gaya to popup nahi aata: App info page kholo
            val asked = Prefs.permAsked
            val k = res.keys.first()
            if (asked.contains(k)) go(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx)))
            else Prefs.permAsked = asked + "," + k
        }
    }
    fun ask(p: PermItem) = p.request(ctx) { arr -> launcher.launch(arr) }

    // wizard: har baar app wapas aate hi agli baaki permission kholo
    LaunchedEffect(resume) {
        val w = wizard ?: return@LaunchedEffect
        if (step < w.size) {
            val n = w.drop(step).firstOrNull { !it.granted(ctx) }
            if (n == null) { wizard = null } else { step = w.indexOf(n) + 1; ask(n) }
        } else wizard = null
    }

    val done = items.count { it.granted(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Wapas") }
        Text("Permission Center", fontSize = 24.sp, color = androidx.compose.ui.graphics.Color.White)
        Text("${items.size} mein se $done ho gaye", color = DIM)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val missing = items.filter { !it.granted(ctx) }
            if (missing.isNotEmpty()) { wizard = missing; step = 0; Bus.resume.value++ }
        }) { Text("Baaki sab ek ke baad ek kholo") }
        Spacer(Modifier.height(8.dp))
        resume.let { items.forEach { p ->
            val ok = p.granted(ctx)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(CARD).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = androidx.compose.ui.graphics.Color.White)
                    Text(p.why, color = DIM, fontSize = 12.sp)
                }
                if (ok) Text("✓", color = TEAL, fontSize = 22.sp) else Button(onClick = { ask(p) }) { Text("Allow") }
            }
        } }
        Spacer(Modifier.height(12.dp))
        Text("Android 13+ par agar Accessibility grey (disabled) dikhe: App info kholo > upar teen dots > \"Allow restricted settings\" > wapas aakar Accessibility on karo.", color = YEL, fontSize = 12.sp)
        Button(onClick = { go(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))) }) { Text("App info kholo") }
        Spacer(Modifier.height(8.dp))
        Text("Accessibility ko Android khud on nahi karne deta, isliye aapko ek baar list mein \"Yesmin\" chun kar toggle dabana hai.", color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(40.dp))
    }
}
