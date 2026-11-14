package iq.samir.friend

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Msg(val role: String, val content: String)

/** كل الإعدادات والمحادثة محفوظة على الهاتف فقط. */
object Store {
    const val K_API = "apiKey"
    const val K_MODEL = "model"
    const val K_WAKE = "wake"
    const val K_USER = "userName"
    const val K_MUTE = "muteBeep"
    const val K_WEB = "webSearch"
    const val K_MEMORY = "memoryMode"
    const val K_VOICE = "voiceName"
    const val K_STATUS = "status"
    const val K_HISTORY = "history"
    const val K_RUNNING = "running"

    const val DEFAULT_MODEL = "openrouter/free"
    const val DEFAULT_WAKE = "سمير"
    private const val MAX_HISTORY = 60

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("samir", Context.MODE_PRIVATE)

    private fun str(c: Context, key: String, def: String): String {
        val v = prefs(c).getString(key, "").orEmpty().trim()
        return if (v.isEmpty()) def else v
    }

    fun apiKey(c: Context) = str(c, K_API, "")
    fun model(c: Context) = str(c, K_MODEL, DEFAULT_MODEL)
    fun wake(c: Context) = str(c, K_WAKE, DEFAULT_WAKE)
    fun userName(c: Context) = str(c, K_USER, "")
    fun muteBeep(c: Context) = prefs(c).getBoolean(K_MUTE, true)
    fun webSearch(c: Context) = prefs(c).getBoolean(K_WEB, true)
    fun memory(c: Context) = prefs(c).getBoolean(K_MEMORY, true)
    fun voice(c: Context) = str(c, K_VOICE, "")
    fun status(c: Context) = str(c, K_STATUS, "متوقف")

    fun put(c: Context, key: String, value: String) {
        prefs(c).edit().putString(key, value).apply()
    }

    fun putBool(c: Context, key: String, value: Boolean) {
        prefs(c).edit().putBoolean(key, value).apply()
    }

    fun setStatus(c: Context, s: String) = put(c, K_STATUS, s)

    fun history(c: Context): MutableList<Msg> {
        val out = mutableListOf<Msg>()
        try {
            val arr = JSONArray(prefs(c).getString(K_HISTORY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                if (role == "user" || role == "assistant") out.add(Msg(role, o.optString("content")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveHistory(c: Context, list: List<Msg>) {
        val arr = JSONArray()
        list.takeLast(MAX_HISTORY).forEach {
            arr.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        put(c, K_HISTORY, arr.toString())
    }

    fun add(c: Context, role: String, content: String) {
        val h = history(c)
        h.add(Msg(role, content))
        saveHistory(c, h)
    }

    fun removeLastUser(c: Context) {
        val h = history(c)
        if (h.isNotEmpty() && h.last().role == "user") {
            h.removeAt(h.size - 1)
            saveHistory(c, h)
        }
    }

    fun clearHistory(c: Context) = put(c, K_HISTORY, "[]")
}
