package com.yesmin.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Gemini ke function-calling tools: phone ko chalane ke saare kaam yahin se hote hain. */
object Tools {
    lateinit var app: Context

    private fun fn(name: String, desc: String, props: Map<String, String> = emptyMap(), req: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            for ((k, v) in props) {
                val (t, d) = v.split("|", limit = 2)
                p.put(k, JSONObject().put("type", t).put("description", d))
            }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", JSONArray(req)))
        }
        return o
    }

    fun declarations(): JSONArray {
        val a = JSONArray()
        a.put(fn("open_app", "Phone mein koi bhi installed app kholo", mapOf("name" to "STRING|App ka naam"), listOf("name")))
        a.put(fn("open_url", "Website browser mein kholo", mapOf("url" to "STRING|Poora URL ya domain"), listOf("url")))
        a.put(fn("web_search", "Google search kholo. Phir read_screen se results padho", mapOf("query" to "STRING|Kya dhoondhna hai"), listOf("query")))
        a.put(fn("read_screen", "Abhi screen par kya hai padho (text, buttons, x/y % mein). Har action ke baad verify ke liye bulao"))
        a.put(fn("tap", "Screen par text ya description wale element par click karo",
            mapOf("text" to "STRING|Button/element ka text", "nth" to "INTEGER|Agar kai milen to kaun sa (0 se)"), listOf("text")))
        a.put(fn("tap_xy", "Screen ke % position par tap karo (read_screen ke x,y)",
            mapOf("x_percent" to "NUMBER|0-100", "y_percent" to "NUMBER|0-100"), listOf("x_percent", "y_percent")))
        a.put(fn("long_press", "Lamba dabao (text ya x,y se)",
            mapOf("text" to "STRING|Element text", "x_percent" to "NUMBER|0-100", "y_percent" to "NUMBER|0-100")))
        a.put(fn("swipe", "Ungli ki disha mein swipe karo", mapOf("direction" to "STRING|up, down, left ya right (ungli ki disha)"), listOf("direction")))
        a.put(fn("scroll", "Page/list scroll karo", mapOf("direction" to "STRING|down (neeche ka hissa dikhao), up, left, right"), listOf("direction")))
        a.put(fn("type_text", "Focused text box mein likho",
            mapOf("text" to "STRING|Kya likhna hai", "replace" to "BOOLEAN|Purana text hatakar likho", "submit" to "BOOLEAN|Likhne ke baad Enter/search dabao"), listOf("text")))
        a.put(fn("press", "System button dabao", mapOf("key" to "STRING|back, home, recents, notifications, quick_settings, lock, screenshot, power_menu, enter"), listOf("key")))
        a.put(fn("wait", "Kuch der ruko (page load ke liye)", mapOf("ms" to "INTEGER|Milliseconds, max 10000"), listOf("ms")))
        a.put(fn("volume", "Awaaz badlo",
            mapOf("action" to "STRING|up, down, mute, unmute, set", "level" to "NUMBER|0-100 (set ke liye)", "stream" to "STRING|media, ring, alarm, voice"), listOf("action")))
        a.put(fn("media", "Music/video control", mapOf("action" to "STRING|play_pause, next, previous, stop"), listOf("action")))
        a.put(fn("brightness", "Screen ki roshni", mapOf("level" to "NUMBER|0-100"), listOf("level")))
        a.put(fn("flashlight", "Torch on/off", mapOf("on" to "BOOLEAN|true = on"), listOf("on")))
        a.put(fn("find_contact", "Contact ka number dhoondho", mapOf("name" to "STRING|Naam"), listOf("name")))
        a.put(fn("call", "Call lagao (pehle user se poochho)",
            mapOf("target" to "STRING|Contact naam ya number", "confirmed" to "BOOLEAN|User ne haan kaha"), listOf("target")))
        a.put(fn("end_call", "Chal rahi call kaato"))
        a.put(fn("answer_call", "Aati hui call uthao"))
        a.put(fn("sms", "SMS bhejo (pehle user se poochho)",
            mapOf("target" to "STRING|Contact naam ya number", "text" to "STRING|Message", "confirmed" to "BOOLEAN|User ne haan kaha"), listOf("target", "text")))
        a.put(fn("alarm", "Alarm lagao", mapOf("hour" to "INTEGER|0-23", "minute" to "INTEGER|0-59", "label" to "STRING|Naam"), listOf("hour", "minute")))
        a.put(fn("timer", "Timer lagao", mapOf("seconds" to "INTEGER|Seconds", "label" to "STRING|Naam"), listOf("seconds")))
        a.put(fn("open_settings", "Settings ka page kholo",
            mapOf("page" to "STRING|wifi, wifi_panel, bluetooth, display, sound, battery, apps, accessibility, notifications, location, airplane, storage, date, language, about, main"), listOf("page")))
        a.put(fn("device_status", "Battery, lock, abhi kaun sa app khula hai, accessibility on hai ya nahi"))
        a.put(fn("remember", "User ke baare mein zaroori baat yaad rakho", mapOf("fact" to "STRING|Chhota fact"), listOf("fact")))
        a.put(fn("recall", "Jo yaad hai wo dekho"))
        a.put(fn("end_conversation", "Baatcheet band karo aur mic band karo"))
        return a
    }

    private fun ok(vararg kv: Pair<String, Any?>) = JSONObject().put("ok", true).also { o -> kv.forEach { o.put(it.first, it.second) } }
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private val actuators = setOf("open_app", "open_url", "web_search", "tap", "tap_xy", "long_press", "swipe", "scroll", "type_text",
        "press", "volume", "media", "brightness", "flashlight", "call", "end_call", "answer_call", "sms", "alarm", "timer", "open_settings")

    private val sensitive = listOf("bank", "paytm", "phonepe", "paisa", "bhim", "upi", "wallet", "authenticator", "bitwarden",
        "keepass", "lastpass", "1password", "dashlane", "passwords", "payments", "razorpay", "mobikwik", "freecharge", "cred.club")

    suspend fun execute(name: String, a: JSONObject): JSONObject {
        val r = try {
            if (Prefs.dryRun && name in actuators) ok("dry_run" to true, "would" to "$name $a")
            else run(name, a)
        } catch (e: IllegalStateException) { err(e.message ?: "error") }
        catch (e: Exception) { err("${e.javaClass.simpleName}: ${e.message}") }
        Bus.log("$name ${a.toString().take(70)} -> ${r.toString().take(110)}")
        return r
    }

    private fun svc(): YesminAccessibilityService =
        YesminAccessibilityService.instance ?: throw IllegalStateException("Accessibility service on nahi hai. Settings > Permissions mein on karein.")

    private fun guard(s: YesminAccessibilityService) {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) throw IllegalStateException("Phone locked hai. Pehle unlock karein.")
        val pkg = s.currentPackage()
        if (pkg != null && sensitive.any { pkg.contains(it, true) })
            throw IllegalStateException("Is screen (banking/payment/password) par automation band hai. Ye aap khud karein.")
    }

    private fun needConfirm(a: JSONObject, what: String): JSONObject? =
        if (Prefs.confirmRisky && !a.optBoolean("confirmed"))
            err("confirmation_needed: user se saaf poochho ($what). Haan milne par confirmed=true ke saath dobara bulao.") else null

    private fun newTask(i: Intent) = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun has(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private suspend fun run(name: String, a: JSONObject): JSONObject {
        when (name) {
            "open_app" -> return openApp(a.getString("name"))
            "open_url" -> {
                var u = a.getString("url").trim()
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                app.startActivity(newTask(Intent(Intent.ACTION_VIEW, Uri.parse(u))))
                return ok("opened" to u)
            }
            "web_search" -> {
                val q = URLEncoder.encode(a.getString("query"), "UTF-8")
                app.startActivity(newTask(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))))
                return ok("opened" to "google search")
            }
            "read_screen" -> {
                val s = svc()
                val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) throw IllegalStateException("Phone locked hai.")
                val pkg = s.currentPackage()
                if (pkg != null && sensitive.any { pkg.contains(it, true) })
                    throw IllegalStateException("Ye banking/payment/password screen hai, main ise nahi padhungi. Aap khud karein.")
                return s.readScreen().put("ok", true)
            }
            "tap" -> { val s = svc(); guard(s)
                val done = s.tapText(a.getString("text"), a.optInt("nth", 0))
                return if (done) ok() else err("'${a.getString("text")}' screen par nahi mila. read_screen karke dekho.") }
            "tap_xy" -> { val s = svc(); guard(s)
                return if (s.tapPct(a.getDouble("x_percent"), a.getDouble("y_percent"))) ok() else err("tap nahi hua") }
            "long_press" -> { val s = svc(); guard(s)
                val done = if (a.has("text")) s.tapText(a.getString("text"), a.optInt("nth", 0), true)
                else s.tapPct(a.getDouble("x_percent"), a.getDouble("y_percent"), 700)
                return if (done) ok() else err("long press nahi hua") }
            "swipe" -> { val s = svc(); guard(s); return if (s.swipeFinger(a.getString("direction"))) ok() else err("swipe nahi hua") }
            "scroll" -> { val s = svc(); guard(s); return if (s.scroll(a.getString("direction"))) ok() else err("scroll nahi hua") }
            "type_text" -> { val s = svc(); guard(s)
                if (s.isPasswordFocused()) throw IllegalStateException("Password field hai, ye aap khud bharein.")
                if (!s.typeText(a.getString("text"), a.optBoolean("replace"))) return err("Koi text box focus mein nahi mila. Pehle text box par tap karo.")
                if (a.optBoolean("submit")) { delay(200); s.pressEnter() }
                return ok() }
            "press" -> { val s = svc()
                val k = a.getString("key")
                if (k == "enter") { guard(s); return if (s.pressEnter()) ok() else err("enter nahi hua") }
                return if (s.global(k)) ok() else err("'$k' is phone par nahi chala") }
            "wait" -> { delay(a.optLong("ms", 800).coerceIn(100, 10000)); return ok() }
            "volume" -> return volume(a)
            "media" -> {
                val code = when (a.getString("action")) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return ok()
            }
            "brightness" -> {
                if (!Settings.System.canWrite(app)) return err("'Modify system settings' permission nahi hai (Permissions mein dein).")
                val v = (a.getDouble("level").coerceIn(0.0, 100.0) * 2.55).toInt()
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                return ok()
            }
            "flashlight" -> {
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return err("Is phone mein torch nahi mila")
                cm.setTorchMode(id, a.getBoolean("on")); return ok()
            }
            "find_contact" -> {
                val l = lookup(a.getString("name"))
                if (!has(Manifest.permission.READ_CONTACTS)) return err("Contacts permission nahi hai.")
                return ok("matches" to JSONArray(l.map { JSONObject().put("name", it.first).put("number", it.second) }))
            }
            "call" -> {
                needConfirm(a, "kisko call lagani hai")?.let { return it }
                if (!has(Manifest.permission.CALL_PHONE)) return err("Phone (CALL_PHONE) permission nahi hai.")
                val num = resolve(a.getString("target")) ?: return err("Contact nahi mila ya kai milte hain. find_contact se dekho.")
                app.startActivity(newTask(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))))
                return ok("calling" to num)
            }
            "end_call" -> {
                if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return err("Phone calls permission nahi hai.")
                val tm = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION") val r = if (Build.VERSION.SDK_INT >= 28) tm.endCall() else false
                return if (r) ok() else err("call nahi kati")
            }
            "answer_call" -> {
                if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return err("Phone calls permission nahi hai.")
                val tm = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall(); return ok()
            }
            "sms" -> {
                needConfirm(a, "kisko aur kya message bhejna hai")?.let { return it }
                if (!has(Manifest.permission.SEND_SMS)) return err("SMS permission nahi hai.")
                val num = resolve(a.getString("target")) ?: return err("Contact nahi mila ya kai milte hain.")
                @Suppress("DEPRECATION")
                val sm = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sm.sendMultipartTextMessage(num, null, sm.divideMessage(a.getString("text")), null, null)
                return ok("sent_to" to num)
            }
            "alarm" -> {
                app.startActivity(newTask(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Yesmin")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)))
                return ok()
            }
            "timer" -> {
                app.startActivity(newTask(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Timer"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)))
                return ok()
            }
            "open_settings" -> {
                val act = when (a.getString("page")) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "wifi_panel" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "date" -> Settings.ACTION_DATE_SETTINGS
                    "language" -> Settings.ACTION_LOCALE_SETTINGS
                    "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val i = Intent(act)
                if (act == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                app.startActivity(newTask(i)); return ok()
            }
            "device_status" -> {
                val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                return ok("battery_percent" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                    "charging" to bm.isCharging, "locked" to km.isKeyguardLocked,
                    "foreground_app" to (YesminAccessibilityService.instance?.currentPackage() ?: "unknown"),
                    "accessibility_on" to (YesminAccessibilityService.instance != null),
                    "time" to java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.US).format(java.util.Date()))
            }
            "remember" -> { Memory.add(a.getString("fact")); return ok() }
            "recall" -> return ok("facts" to JSONArray(Memory.all()))
            "end_conversation" -> { VoiceService.stopLater(); return ok() }
        }
        return err("Unknown tool: $name")
    }

    private fun volume(a: JSONObject): JSONObject {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (a.optString("stream", "media")) {
            "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
            "voice" -> AudioManager.STREAM_VOICE_CALL; else -> AudioManager.STREAM_MUSIC }
        when (a.getString("action")) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, (a.getDouble("level").coerceIn(0.0, 100.0) / 100.0 * max).toInt(), AudioManager.FLAG_SHOW_UI)
            }
            else -> return err("action samajh nahi aaya")
        }
        return ok("level" to am.getStreamVolume(stream), "max" to am.getStreamMaxVolume(stream))
    }

    private fun norm(s: String) = s.lowercase().replace("[^a-z0-9\\u0900-\\u097f]".toRegex(), "")

    private val aliases = mapOf("व्हाट्सएप" to "whatsapp", "वॉट्सऐप" to "whatsapp", "यूट्यूब" to "youtube", "क्रोम" to "chrome",
        "कैमरा" to "camera", "गैलरी" to "gallery", "सेटिंग" to "settings", "सेटिंग्स" to "settings", "फोन" to "phone",
        "wa" to "whatsapp", "yt" to "youtube", "insta" to "instagram", "gpay" to "google pay", "इंस्टाग्राम" to "instagram")

    private fun openApp(query: String): JSONObject {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val q = norm(aliases[query.trim().lowercase()] ?: query)
        if (q.isEmpty()) return err("app ka naam khali")
        fun score(label: String): Int { val l = norm(label)
            return when { l == q -> 100; l.startsWith(q) -> 80; l.contains(q) -> 60; q.contains(l) && l.length > 2 -> 40; else -> 0 } }
        val best = apps.map { it to score(it.first) }.filter { it.second > 0 }.maxByOrNull { it.second }
            ?: return err("'$query' naam ka app nahi mila")
        val li = pm.getLaunchIntentForPackage(best.first.second) ?: return err("app khul nahi sakta")
        app.startActivity(newTask(li))
        return ok("opened" to best.first.first)
    }

    private fun lookup(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (!has(Manifest.permission.READ_CONTACTS)) return out
        app.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null)?.use { c ->
            while (c.moveToNext() && out.size < 8) out += c.getString(0) to c.getString(1)
        }
        return out.distinctBy { it.second.filter { ch -> ch.isDigit() }.takeLast(10) }
    }

    private fun resolve(target: String): String? {
        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6 && target.count { it.isLetter() } == 0) return digits
        val m = lookup(target)
        return if (m.size == 1) m[0].second else null
    }
}
