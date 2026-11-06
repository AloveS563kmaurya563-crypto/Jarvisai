package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Talks to Gemini, runs the function-calling loop, and remembers a short conversation. */
class Brain(private val ctx: Context) {

    private val history = JSONArray() // plain text turns only (user / model)

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return """
You are Jarvis, a voice assistant that controls the user's Android phone. Current local time: $now.

Your replies are spoken aloud by text-to-speech: keep them very short (one or two sentences), with no markdown, no emojis, no lists, no URLs.
Reply in the language the user spoke (Hindi, Hinglish or English). Write Hindi in Devanagari script.

Use your tools to actually do things. For tasks inside other apps: open_app first, then read_screen, then tap_text / type_text / scroll, and read_screen again after each step to verify what happened. Screens need a moment to load, so if read_screen shows the old screen, try again.
Never say a task is done unless tool results confirm it; if something failed, say so briefly and what you tried.
Never make payments or purchases, and never type passwords, OTPs or PINs: stop and ask the user to do that final step. For deleting data or other hard-to-undo actions, ask for a spoken yes first.
For simple questions that need no phone action, just answer.
""".trimIndent()
    }

    // Convert our tool definitions into Gemini function declarations.
    private fun declarations(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val schema = t.getJSONObject("input_schema")
            val d = JSONObject()
                .put("name", t.getString("name"))
                .put("description", t.getString("description"))
            if (schema.getJSONObject("properties").length() > 0) d.put("parameters", convert(schema))
            out.put(d)
        }
        return out
    }

    private fun convert(s: JSONObject): JSONObject {
        val r = JSONObject().put("type", s.getString("type").uppercase())
        s.optString("description").takeIf { it.isNotEmpty() }?.let { r.put("description", it) }
        s.optJSONArray("enum")?.let { r.put("enum", it) }
        s.optJSONObject("properties")?.let { p ->
            val np = JSONObject()
            for (k in p.keys()) np.put(k, convert(p.getJSONObject(k)))
            r.put("properties", np)
        }
        s.optJSONArray("required")?.takeIf { it.length() > 0 }?.let { r.put("required", it) }
        return r
    }

    private fun textTurn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun ask(userText: String): String {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return "पहले ऐप में अपनी API key डालिए।"

        val contents = JSONArray()
        for (i in 0 until history.length()) contents.put(history.get(i))
        contents.put(textTurn("user", userText))

        val decls = declarations(Tools.definitions())
        val system = systemPrompt()
        var finalText = ""

        for (step in 0 until 12) {
            val resp = GeminiClient.call(key, Prefs.model(ctx), system, decls, contents)
            val parts = resp.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts == null) {
                finalText = "मुझे समझ नहीं आया, दोबारा बोलिए।"
                break
            }
            // keep the model's parts exactly as returned (needed for thought signatures)
            contents.put(JSONObject().put("role", "model").put("parts", parts))

            val sb = StringBuilder()
            val responses = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val call = p.optJSONObject("functionCall")
                if (call != null) {
                    val name = call.getString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    JarvisLog.add("⚙ $name $args")
                    val out = try {
                        Tools.execute(ctx, name, args)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    JarvisLog.add("   → ${out.take(200)}")
                    responses.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name)
                                .put("response", JSONObject().put("result", out))
                        )
                    )
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    sb.append(p.getString("text"))
                }
            }
            if (sb.isNotEmpty()) finalText = sb.toString().trim()
            if (responses.length() == 0) break
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }

        if (finalText.isBlank()) finalText = "ठीक है।"

        history.put(textTurn("user", userText))
        history.put(textTurn("model", finalText))
        while (history.length() > 12) history.remove(0)
        return finalText
    }
}
