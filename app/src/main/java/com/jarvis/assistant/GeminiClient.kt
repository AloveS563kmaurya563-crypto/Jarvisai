package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Google Gemini API client (free tier, no external libraries). */
object GeminiClient {

    fun call(
        apiKey: String,
        model: String,
        system: String,
        functionDeclarations: JSONArray,
        contents: JSONArray
    ): JSONObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)

            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
                .put("contents", contents)
                .put(
                    "tools",
                    JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations))
                )
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("API error $code: $text")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
