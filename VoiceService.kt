package com.yesmin.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 24 ghante wala mic: foreground service (microphone), wake lock, echo-cancelled mic jo bolte waqt bhi khula rehta hai,
 * Gemini Live se dono taraf streaming, barge-in, auto-reconnect, watchdog.
 */
class VoiceService : Service(), LiveClient.Listener {
    companion object {
        const val CH = "yesmin_voice"
        const val NID = 1
        const val A_STOP = "com.yesmin.app.STOP"
        const val A_MUTE = "com.yesmin.app.MUTE"
        @Volatile private var running = false

        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, VoiceService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, VoiceService::class.java).setAction(A_STOP)) }
        fun mute(c: Context) { c.startService(Intent(c, VoiceService::class.java).setAction(A_MUTE)) }
        fun stopLater() {
            val c = Tools.app
            CoroutineScope(Dispatchers.Default).launch { delay(4500); if (running) stop(c) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var live: LiveClient? = null
    private var mic: MicStream? = null
    private var player: PcmPlayer? = null
    @Volatile private var ready = false
    @Volatile private var stopping = false
    private var started = false
    private var resumeHandle: String? = null
    private var retry = 0
    private var wake: PowerManager.WakeLock? = null
    private var prevMode = AudioManager.MODE_NORMAL

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_STOP -> { shutdown(); return START_NOT_STICKY }
            A_MUTE -> { setMuted(!Bus.muted.value); return START_STICKY }
        }
        if (!started) {
            started = true
            if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
            begin()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NID, notif(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        true
    } catch (e: Exception) { Bus.log("Foreground start fail: ${e.message}"); false }

    private fun begin() {
        running = true; stopping = false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Mic permission nahi hai", fatal = true); return
        }
        Bus.state.value = VState.CONNECTING; Bus.status.value = "Connect ho rahi hoon..."
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yesmin:voice").apply { acquire() }
        setupAudioRoute()
        player = PcmPlayer()
        if (Prefs.edgeGlow) EdgeGlow.show(applicationContext)
        startMic()
        scope.launch { connect() }
        scope.launch { watchdog() }
        scope.launch { ticker() }
    }

    private fun startMic() {
        mic?.stop()
        val m = MicStream { buf, n -> if (ready) live?.sendAudio(buf, n) }
        m.muted = Bus.muted.value
        try { m.start(); mic = m } catch (e: Exception) { Bus.log("Mic start fail: ${e.message}") }
    }

    private suspend fun connect() {
        val key = Prefs.apiKey
        if (key.isBlank()) { fail("API key nahi hai. Settings mein daalein.", fatal = true); return }
        var model = Prefs.liveModel
        if (model.isBlank()) {
            model = try { Gemini.pickLiveModel(key) } catch (e: Exception) { fail("Model list nahi mili: ${e.message}", fatal = true); return }
                ?: run { fail("Is key par Live model nahi mila", fatal = true); return }
        }
        ready = false
        live?.close()
        val l = LiveClient(this); live = l
        Bus.log("Live connect: $model")
        l.connect(key, Live.setup(model, resumeHandle))
    }

    private fun fail(msg: String, fatal: Boolean = false) {
        Bus.state.value = VState.ERROR; Bus.status.value = msg; Bus.log("ERROR: $msg")
        if (fatal) scope.launch { delay(2500); shutdown(keepError = true) }
    }

    private suspend fun watchdog() {
        while (scope.isActive) {
            delay(3000)
            val m = mic
            if (!stopping && m != null && System.currentTimeMillis() - m.lastFrameAt > 3500) {
                Bus.log("Mic frames ruk gaye, restart"); startMic()
            }
        }
    }

    private suspend fun ticker() {
        while (scope.isActive) {
            val speaking = Bus.state.value == VState.SPEAKING
            val lv = if (speaking) (player?.level ?: 0f) else (mic?.level ?: 0f)
            Bus.level.value = lv
            player?.let { it.level = it.level * 0.8f }
            delay(50)
        }
    }

    private fun setMuted(b: Boolean) {
        Bus.muted.value = b; mic?.muted = b
        getSystemService(NotificationManager::class.java).notify(NID, notif())
    }

    // ---- LiveClient.Listener ----
    override fun onReady() {
        ready = true; retry = 0
        Bus.state.value = VState.LISTENING; Bus.status.value = "${Prefs.assistantName} sun rahi hai"
    }
    override fun onAudio(pcm: ByteArray) { Bus.state.value = VState.SPEAKING; player?.write(pcm) }
    override fun onInterrupted() { player?.flush(); if (ready) Bus.state.value = VState.LISTENING }
    override fun onTurnComplete() {
        scope.launch { player?.awaitDrain(); if (Bus.state.value == VState.SPEAKING) Bus.state.value = VState.LISTENING }
    }
    override fun onToolCall(id: String, name: String, args: JSONObject) {
        Bus.state.value = VState.THINKING
        scope.launch {
            val r = Tools.execute(name, args)
            live?.sendToolResponse(id, name, r)
        }
    }
    override fun onText(who: String, text: String) { Bus.say(who, text) }
    override fun onResumeHandle(h: String) { resumeHandle = h }
    override fun onClosed(reason: String, fatal: Boolean) {
        ready = false
        if (stopping) return
        Bus.log("Live closed: $reason")
        if (fatal && resumeHandle != null) { resumeHandle = null; scope.launch { delay(500); if (!stopping) connect() }; return }
        if (fatal) { fail("Connection nahi ban paaya: $reason (key/model check karein)", fatal = true); return }
        scope.launch {
            retry++
            val d = minOf(15000L, 500L * (1L shl minOf(retry, 5)))
            Bus.state.value = VState.CONNECTING; Bus.status.value = "Dobara connect ho rahi hoon..."
            delay(d)
            if (!stopping) connect()
        }
    }

    // ---- audio route ----
    @Suppress("DEPRECATION")
    private fun setupAudioRoute() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val pick = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            pick?.let { am.setCommunicationDevice(it) }
        } else {
            am.isSpeakerphoneOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = false
            am.mode = prevMode
        } catch (_: Exception) {}
    }

    // ---- notification ----
    private fun pi(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, VoiceService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Yesmin voice", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${Prefs.assistantName} sun rahi hai")
            .setContentText(if (Bus.muted.value) "Mic mute hai" else "Mic chalu hai")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, if (Bus.muted.value) "Unmute" else "Mute", pi(A_MUTE))
            .addAction(0, "Stop", pi(A_STOP)).build()
    }

    private fun shutdown(keepError: Boolean = false) {
        if (stopping && !running) return
        stopping = true; ready = false; running = false; started = false
        live?.close(); live = null
        mic?.stop(); mic = null
        player?.release(); player = null
        EdgeGlow.hide(); restoreAudio()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (!keepError) Bus.state.value = VState.IDLE
        Bus.level.value = 0f; Bus.muted.value = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (running) shutdown(); super.onDestroy() }
}
