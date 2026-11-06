package com.jarvis.assistant

import android.Manifest
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
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Jarvis can do on the phone. Runs on a background thread. */
object Tools {

    // ---------- tool definitions sent to Claude ----------

    private fun sProp(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun iProp(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun bProp(d: String) = JSONObject().put("type", "boolean").put("description", d)
    private fun eProp(d: String, vararg v: String) =
        JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v.toList()))

    private fun tool(
        name: String,
        desc: String,
        props: JSONObject = JSONObject(),
        required: List<String> = emptyList()
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", desc)
        .put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required))
        )

    fun definitions(): JSONArray = JSONArray().apply {
        put(
            tool(
                "open_app",
                "Open an installed app by its name as shown on the phone's launcher (use the English/brand name, e.g. 'WhatsApp', 'YouTube', 'Camera', 'Settings').",
                JSONObject().put("name", sProp("App name")), listOf("name")
            )
        )
        put(
            tool(
                "open_url",
                "Open a web address in the browser.",
                JSONObject().put("url", sProp("Full URL starting with https://")), listOf("url")
            )
        )
        put(
            tool(
                "web_search",
                "Search Google in the browser for the given query.",
                JSONObject().put("query", sProp("Search text")), listOf("query")
            )
        )
        put(
            tool(
                "call_contact",
                "Place a phone call to a saved contact name or a phone number.",
                JSONObject().put("who", sProp("Contact name or number")), listOf("who")
            )
        )
        put(
            tool(
                "send_sms",
                "Send a text message (SMS) to a saved contact name or a number.",
                JSONObject().put("to", sProp("Contact name or number"))
                    .put("message", sProp("Message text")),
                listOf("to", "message")
            )
        )
        put(
            tool(
                "set_alarm",
                "Set an alarm for a clock time (24-hour).",
                JSONObject().put("hour", iProp("0-23")).put("minute", iProp("0-59"))
                    .put("label", sProp("Optional label")),
                listOf("hour", "minute")
            )
        )
        put(
            tool(
                "set_timer",
                "Start a countdown timer.",
                JSONObject().put("seconds", iProp("Duration in seconds"))
                    .put("label", sProp("Optional label")),
                listOf("seconds")
            )
        )
        put(
            tool(
                "flashlight",
                "Turn the flashlight on or off.",
                JSONObject().put("on", bProp("true = on")), listOf("on")
            )
        )
        put(
            tool(
                "set_volume",
                "Set a volume level in percent.",
                JSONObject().put("percent", iProp("0-100"))
                    .put("stream", eProp("Which volume", "media", "ring", "alarm")),
                listOf("percent")
            )
        )
        put(
            tool(
                "set_brightness",
                "Set screen brightness in percent (needs the 'modify system settings' permission).",
                JSONObject().put("percent", iProp("0-100")), listOf("percent")
            )
        )
        put(
            tool(
                "media_control",
                "Control music/video playback.",
                JSONObject().put(
                    "action",
                    eProp("Action", "play_pause", "next", "previous", "stop")
                ), listOf("action")
            )
        )
        put(
            tool(
                "open_settings",
                "Open a page of the phone's Settings app. Wi-Fi/Bluetooth/airplane mode cannot be switched silently on modern Android, so open the page and then use read_screen + tap_text to flip the switch.",
                JSONObject().put(
                    "page",
                    eProp(
                        "Settings page", "main", "wifi", "bluetooth", "airplane", "display",
                        "sound", "battery", "apps", "location"
                    )
                ), listOf("page")
            )
        )
        put(
            tool(
                "press_button",
                "Press a system button/action: back, home, recents, notifications (pull down shade), quick_settings, screenshot, lock_screen, power_menu.",
                JSONObject().put(
                    "button",
                    eProp(
                        "Button", "back", "home", "recents", "notifications", "quick_settings",
                        "screenshot", "lock_screen", "power_menu"
                    )
                ), listOf("button")
            )
        )
        put(
            tool(
                "read_screen",
                "Read what is currently on the phone screen: visible texts, buttons and input fields with their coordinates. Use it to see the state of any app."
            )
        )
        put(
            tool(
                "tap_text",
                "Tap the on-screen element whose text or description contains the given text.",
                JSONObject().put("text", sProp("Text of the element")), listOf("text")
            )
        )
        put(
            tool(
                "type_text",
                "Type text into the focused (or first) input field on screen, replacing its content. Set submit=true to press enter/search afterwards.",
                JSONObject().put("text", sProp("Text to type"))
                    .put("submit", bProp("Press enter after typing")),
                listOf("text")
            )
        )
        put(
            tool(
                "scroll",
                "Scroll the current screen.",
                JSONObject().put("direction", eProp("Direction", "down", "up")), listOf("direction")
            )
        )
        put(
            tool(
                "tap_xy",
                "Tap at screen coordinates (use coordinates from read_screen) for elements that have no text.",
                JSONObject().put("x", iProp("X pixel")).put("y", iProp("Y pixel")),
                listOf("x", "y")
            )
        )
        put(
            tool(
                "swipe",
                "Swipe between two screen coordinates.",
                JSONObject().put("x1", iProp("Start X")).put("y1", iProp("Start Y"))
                    .put("x2", iProp("End X")).put("y2", iProp("End Y")),
                listOf("x1", "y1", "x2", "y2")
            )
        )
        put(
            tool(
                "get_info",
                "Get the current date, time, battery level and screen size."
            )
        )
        put(tool("wait", "Wait a couple of seconds for the screen to finish loading."))
    }

    // ---------- execution ----------

    fun execute(ctx: Context, name: String, a: JSONObject): String = when (name) {
        "open_app" -> openApp(ctx, a.getString("name"))
        "open_url" -> {
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))))
            sleep(1200); "Opened the link."
        }
        "web_search" -> {
            val q = Uri.encode(a.getString("query"))
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
            sleep(1500); "Opened Google search."
        }
        "call_contact" -> callContact(ctx, a.getString("who"))
        "send_sms" -> sendSms(ctx, a.getString("to"), a.getString("message"))
        "set_alarm" -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            launch(ctx, i)
            "Alarm set for %02d:%02d.".format(a.getInt("hour"), a.getInt("minute"))
        }
        "set_timer" -> {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            launch(ctx, i)
            "Timer started for ${a.getInt("seconds")} seconds."
        }
        "flashlight" -> flashlight(ctx, a.getBoolean("on"))
        "set_volume" -> setVolume(ctx, a.getInt("percent"), a.optString("stream", "media"))
        "set_brightness" -> setBrightness(ctx, a.getInt("percent"))
        "media_control" -> mediaControl(ctx, a.getString("action"))
        "open_settings" -> openSettings(ctx, a.getString("page"))
        "press_button" -> withA11y { it.press(a.getString("button")) }
        "read_screen" -> withA11y { it.readScreen() }
        "tap_text" -> withA11y { it.clickText(a.getString("text")) }
        "type_text" -> withA11y { it.typeText(a.getString("text"), a.optBoolean("submit", false)) }
        "scroll" -> withA11y { it.scroll(a.getString("direction") == "down") }
        "tap_xy" -> withA11y { it.tap(a.getInt("x").toFloat(), a.getInt("y").toFloat()) }
        "swipe" -> withA11y {
            it.swipe(
                a.getInt("x1").toFloat(), a.getInt("y1").toFloat(),
                a.getInt("x2").toFloat(), a.getInt("y2").toFloat(), 350
            )
        }
        "get_info" -> info(ctx)
        "wait" -> { sleep(2000); "Waited." }
        else -> "Unknown tool: $name"
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun withA11y(block: (JarvisAccessibilityService) -> String): String {
        val s = JarvisAccessibilityService.instance
            ?: return "Accessibility service is OFF. Tell the user to enable Jarvis in Settings > Accessibility."
        val r = block(s)
        sleep(700) // let the UI settle before the next step
        return r
    }

    /** Start activities through the accessibility service when possible: it is allowed to do so from the background. */
    private fun launch(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccessibilityService.instance ?: ctx
        c.startActivity(i)
    }

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ---------- individual actions ----------

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.lowercase().trim()
        fun label(i: android.content.pm.ResolveInfo) = i.loadLabel(pm).toString().lowercase()
        val match = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { q.contains(label(it)) && label(it).length > 2 }
        if (match == null) {
            val some = apps.map { it.loadLabel(pm).toString() }.sorted().take(60)
            return "No app named '$name'. Installed apps include: ${some.joinToString(", ")}"
        }
        val pkg = match.activityInfo.packageName
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "Cannot launch ${match.loadLabel(pm)}."
        launch(ctx, i)
        sleep(1800)
        return "Opened ${match.loadLabel(pm)}."
    }

    private fun findNumber(ctx: Context, who: String): String? {
        if (who.none { it.isLetter() } && who.any { it.isDigit() }) {
            return who.filter { it.isDigit() || it == '+' }
        }
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$who%"), null
        ) ?: return null
        c.use {
            if (it.moveToFirst()) return it.getString(0).filter { ch -> ch.isDigit() || ch == '+' }
        }
        return null
    }

    private fun callContact(ctx: Context, who: String): String {
        if (!granted(ctx, Manifest.permission.CALL_PHONE)) return "Phone-call permission not granted."
        val num = findNumber(ctx, who)
            ?: return "Could not find a number for '$who' (check contacts permission / spelling)."
        launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
        return "Calling $who."
    }

    private fun sendSms(ctx: Context, to: String, message: String): String {
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return "SMS permission not granted."
        val num = findNumber(ctx, to) ?: return "Could not find a number for '$to'."
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(num, null, parts, null, null)
        return "SMS sent to $to."
    }

    private fun flashlight(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun setVolume(ctx: Context, percent: Int, stream: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(type)
        val level = (max * percent.coerceIn(0, 100) / 100.0).toInt()
        am.setStreamVolume(type, level, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $percent%."
    }

    private fun setBrightness(ctx: Context, percent: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            return "Need 'modify system settings' permission: tell the user to tap that button in the Jarvis app."
        }
        val v = (255 * percent.coerceIn(0, 100) / 100.0).toInt()
        Settings.System.putInt(
            ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        return "Brightness set to $percent%."
    }

    private fun mediaControl(ctx: Context, action: String): String {
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Sent $action."
    }

    private fun openSettings(ctx: Context, page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(ctx, Intent(action))
        sleep(1200)
        return "Opened $page settings."
    }

    private fun info(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val dm = ctx.resources.displayMetrics
        return "Time: $now. Battery: $battery%. Screen: ${dm.widthPixels}x${dm.heightPixels}px. " +
            "Accessibility control: ${if (JarvisAccessibilityService.instance != null) "ON" else "OFF"}."
    }
}
