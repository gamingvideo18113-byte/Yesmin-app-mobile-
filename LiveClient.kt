package com.yesmin.app

import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Gemini Live API ka WebSocket client: dono taraf audio streaming. */
class LiveClient(private val l: Listener) {
    interface Listener {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onText(who: String, text: String)
        fun onResumeHandle(h: String)
        fun onClosed(reason: String, fatal: Boolean)
    }

    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closedByUs = false
    private val finished = AtomicBoolean(false)

    fun connect(key: String, setup: JSONObject) {
        val setupJson = setup.toString()
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = Gemini.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done("code $code $reason", code == 1007 || code == 1008)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done("code $code $reason", code == 1007 || code == 1008)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val c = response?.code
                done((t.message ?: "network error") + (c?.let { " HTTP $it" } ?: ""), c == 400 || c == 401 || c == 403 || c == 404)
            }
        })
    }

    private fun done(reason: String, fatal: Boolean) {
        ready = false
        if (closedByUs) return
        if (finished.compareAndSet(false, true)) l.onClosed(reason, fatal)
    }

    private fun handle(s: String) {
        try {
            val j = JSONObject(s)
            if (j.has("setupComplete")) { ready = true; l.onReady(); return }
            j.optJSONObject("sessionResumptionUpdate")?.let {
                val h = it.optString("newHandle")
                if (h.isNotBlank() && it.optBoolean("resumable", true)) l.onResumeHandle(h)
            }
            j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
                for (i in 0 until fc.length()) {
                    val c = fc.getJSONObject(i)
                    l.onToolCall(c.optString("id"), c.getString("name"), c.optJSONObject("args") ?: JSONObject())
                }
            }
            j.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted")) l.onInterrupted()
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
                    for (i in 0 until ps.length()) {
                        val d = ps.getJSONObject(i).optJSONObject("inlineData") ?: continue
                        if (d.optString("mimeType").startsWith("audio"))
                            l.onAudio(Base64.decode(d.getString("data"), Base64.DEFAULT))
                    }
                }
                sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) l.onText("Aap", it) }
                sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) l.onText(Prefs.assistantName, it) }
                if (sc.optBoolean("turnComplete")) l.onTurnComplete()
            }
        } catch (e: Exception) {
            Bus.log("Live parse error: ${e.message}")
        }
    }

    fun sendAudio(buf: ByteArray, n: Int) {
        if (!ready) return
        val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun sendToolResponse(id: String, name: String, resp: JSONObject) {
        val m = JSONObject().put("toolResponse", JSONObject().put("functionResponses",
            JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", resp))))
        ws?.send(m.toString())
    }

    fun close() {
        closedByUs = true; ready = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
    }
}

object Live {
    fun setup(model: String, handle: String?): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice))))
        val s = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.system(true)))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations())))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().apply { if (handle != null) put("handle", handle) })
        return JSONObject().put("setup", s)
    }
}
