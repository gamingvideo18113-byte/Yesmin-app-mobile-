package com.yesmin.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gemini REST: key test, model list, TTS preview, text chat (tools ke saath). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun call(req: Request): String {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = try { JSONObject(body).getJSONObject("error").getString("message") } catch (e: Exception) { body.take(200) }
                throw IOException("HTTP ${r.code}: $msg")
            }
            return body
        }
    }

    private fun get(key: String, url: String) = call(Request.Builder().url(url).header("x-goog-api-key", key).build())

    suspend fun listModels(key: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val out = mutableListOf<JSONObject>()
        var token: String? = null
        do {
            val url = "$BASE/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: "")
            val j = JSONObject(get(key, url))
            val arr = j.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            token = j.optString("nextPageToken").ifBlank { null }
        } while (token != null)
        out
    }

    suspend fun testKey(key: String): Result<Int> = runCatching { listModels(key).size }

    private fun JSONObject.supports(m: String) =
        optJSONArray("supportedGenerationMethods")?.toString()?.contains(m) == true

    suspend fun pickLiveModel(key: String): String? {
        val ms = listModels(key).filter { it.supports("bidiGenerateContent") }
            .map { it.getString("name").removePrefix("models/") }
        return ms.filter { it.contains("native-audio") }.sortedDescending().firstOrNull()
            ?: ms.sortedDescending().firstOrNull()
    }

    suspend fun pickTtsModel(key: String): String? =
        listModels(key).filter { it.supports("generateContent") }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.contains("tts") }.sortedDescending().firstOrNull()

    suspend fun generate(key: String, model: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(call(Request.Builder().url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()))
    }

    /** Voice preview: text ko chune hue voice mein bolwakar raw PCM (24kHz) deta hai. */
    suspend fun tts(key: String, model: String, voice: String, text: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val r = generate(key, model, body)
        val data = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(data, Base64.DEFAULT)
    }
}

/** Text chat: tools ke saath chhota agent loop. */
object ChatEngine {
    suspend fun reply(history: JSONArray): String {
        val key = Prefs.apiKey
        if (key.isBlank()) return "API key nahi hai. Settings mein daalein."
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations()))
        repeat(8) {
            val body = JSONObject()
                .put("contents", history)
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.system(false)))))
                .put("tools", tools)
            val r = Gemini.generate(key, Prefs.chatModel, body)
            val content = r.getJSONArray("candidates").getJSONObject(0).optJSONObject("content")
                ?: return "(jawab khali aaya)"
            val parts = content.optJSONArray("parts") ?: JSONArray()
            history.put(content)
            val calls = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.has("functionCall") }
            if (calls.isEmpty()) {
                return (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optString("text").ifBlank { null } }.joinToString("")
            }
            val resp = JSONArray()
            for (p in calls) {
                val fc = p.getJSONObject("functionCall")
                val res = Tools.execute(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
                resp.put(JSONObject().put("functionResponse", JSONObject().put("name", fc.getString("name")).put("response", res)))
            }
            history.put(JSONObject().put("role", "user").put("parts", resp))
        }
        return "Kaam bahut lamba ho gaya, 8 step ke baad ruk gayi. Bataiye aage kya karun?"
    }
}
