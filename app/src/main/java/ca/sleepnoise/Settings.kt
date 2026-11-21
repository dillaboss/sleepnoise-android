package ca.sleepnoise

import android.content.Context
import org.json.JSONObject

/** All user settings, stored as one JSON object in SharedPreferences. Keys starting with "_" are internal. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sleepnoise", Context.MODE_PRIVATE)
    private var data: JSONObject = load()

    private fun load(): JSONObject {
        val o = JSONObject(DEFAULTS.toString())
        runCatching { JSONObject(prefs.getString("settings", "{}")!!) }.getOrNull()?.let { saved ->
            saved.keys().forEach { k -> o.put(k, saved.get(k)) }
        }
        return o
    }

    @Synchronized fun num(k: String): Double = data.optDouble(k, DEFAULTS.optDouble(k, 0.0))
    @Synchronized fun bool(k: String): Boolean = data.optBoolean(k, DEFAULTS.optBoolean(k, false))
    @Synchronized fun str(k: String): String = data.optString(k, DEFAULTS.optString(k, ""))
    fun f(k: String) = num(k).toFloat()

    @Synchronized fun put(k: String, v: Any?) {
        data.put(k, v ?: JSONObject.NULL)
        prefs.edit().putString("settings", data.toString()).apply()
    }

    @Synchronized fun reset() {
        val keep = JSONObject()
        data.keys().forEach { k -> if (k.startsWith("_")) keep.put(k, data.get(k)) }
        data = JSONObject(DEFAULTS.toString()); keep.keys().forEach { data.put(it, keep.get(it)) }
        prefs.edit().putString("settings", data.toString()).apply()
    }

    /** Public settings for the UI (internal keys left out). */
    @Synchronized fun toJson(): JSONObject {
        val o = JSONObject()
        data.keys().forEach { k -> if (!k.startsWith("_")) o.put(k, data.get(k)) }
        return o
    }

    val shape get() = NoiseShape(num("tone"), num("rumble"), num("color"))

    companion object {
        /** Sound defaults are fitted to MyBrownNoise.mp3 (spectrum within ~1 dB, same loudness at 75%). */
        val DEFAULTS: JSONObject = JSONObject()
            .put("volume", 0.75).put("tone", 168).put("rumble", 65).put("color", 0.7).put("width", 1)
            .put("waves", 0).put("wavePeriod", 12).put("fadeIn", 8).put("fadeOut", 4)
            .put("introOn", true).put("introVol", 0.8).put("noiseDelay", 3.5)
            .put("voiceOn", true).put("wake", "hey brownie").put("chime", true).put("chimeVol", 0.25)
            .put("duck", 0.3).put("minConf", 0.6).put("micFx", false).put("micGain", 1)
            .put("autoStart", "").put("autoStop", "")
            .put("dimAfter", 20).put("showClock", true).put("resume", true).put("keepScreenOn", true)
    }
}
