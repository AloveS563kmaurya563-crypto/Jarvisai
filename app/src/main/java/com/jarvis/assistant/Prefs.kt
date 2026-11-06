package com.jarvis.assistant

import android.content.Context

object Prefs {
    // A model that is free of charge on the Gemini API free tier. Editable in the app.
    const val DEFAULT_MODEL = "gemini-3.6-flash"

    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun model(c: Context): String =
        (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", key.trim()).putString("model", model.trim()).apply()
    }
}
