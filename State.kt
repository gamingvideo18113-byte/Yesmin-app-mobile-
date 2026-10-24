package com.yesmin.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

/** Poore app mein shared live state (UI, service, overlay sab yahin se padhte hain). */
object Bus {
    val state = MutableStateFlow(VState.IDLE)
    val muted = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val status = MutableStateFlow("")
    val log = MutableStateFlow<List<String>>(emptyList())
    val transcript = MutableStateFlow<List<String>>(emptyList())
    val resume = MutableStateFlow(0)

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.value = (listOf("$t  $msg") + log.value).take(300)
    }

    /** Transcript ke tukde ek hi speaker ki line mein jud jaate hain. */
    fun say(who: String, piece: String) {
        if (piece.isBlank()) return
        val cur = transcript.value
        val last = cur.lastOrNull()
        transcript.value = if (last != null && last.startsWith("$who: ")) {
            cur.dropLast(1) + (last + piece)
        } else (cur + "$who: ${piece.trimStart()}").takeLast(60)
    }
}

/** Sab settings. Har save commit() se hota hai, taaki "save nahi hua" wali dikkat na aaye. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.applicationContext.getSharedPreferences("yesmin", Context.MODE_PRIVATE) }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Float -> e.putFloat(k, v)
            is Int -> e.putInt(k, v)
        }
        e.commit()
    }

    fun cleanKey(v: String) = v.trim().trim('"', '\'').replace("\\s".toRegex(), "")

    var apiKey: String
        get() = s("apiKey", ""); set(v) = put("apiKey", cleanKey(v))
    var liveModel: String
        get() = s("liveModel", ""); set(v) = put("liveModel", v.trim())
    var chatModel: String
        get() = s("chatModel", "gemini-flash-latest"); set(v) = put("chatModel", v.trim())
    var ttsModel: String
        get() = s("ttsModel", ""); set(v) = put("ttsModel", v.trim())
    var voice: String
        get() = s("voice", "Kore"); set(v) = put("voice", v)
    var assistantName: String
        get() = s("assistantName", "Yesmin"); set(v) = put("assistantName", v.trim().ifBlank { "Yesmin" })
    var userName: String
        get() = s("userName", ""); set(v) = put("userName", v.trim())
    var language: String
        get() = s("language", "Auto"); set(v) = put("language", v)
    var personality: String
        get() = s("personality", "Friendly"); set(v) = put("personality", v)
    var style: String
        get() = s("style", "Balanced"); set(v) = put("style", v)
    var emotion: Float
        get() = sp.getFloat("emotion", 0.7f); set(v) = put("emotion", v)
    var edgeGlow: Boolean
        get() = sp.getBoolean("edgeGlow", true); set(v) = put("edgeGlow", v)
    var confirmRisky: Boolean
        get() = sp.getBoolean("confirmRisky", true); set(v) = put("confirmRisky", v)
    var dryRun: Boolean
        get() = sp.getBoolean("dryRun", false); set(v) = put("dryRun", v)
    var updateUrl: String
        get() = s("updateUrl", ""); set(v) = put("updateUrl", v.trim())
    var memory: String
        get() = s("memory", "[]"); set(v) = put("memory", v)
    var permAsked: String
        get() = s("permAsked", ""); set(v) = put("permAsked", v)
}

/** Lambi yaaddaasht: user ke bataye facts, har session mein system prompt mein jaate hain. */
object Memory {
    fun all(): List<String> {
        val a = try { JSONArray(Prefs.memory) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getString(it) }
    }
    fun add(fact: String) {
        val f = fact.trim(); if (f.isEmpty()) return
        val list = (all() + f).distinct().takeLast(100)
        Prefs.memory = JSONArray(list).toString()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Tools.app = applicationContext
    }
}
