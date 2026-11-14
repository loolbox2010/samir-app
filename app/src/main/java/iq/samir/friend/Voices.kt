package iq.samir.friend

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/** اختيار أوضح صوت عربي، وتجهيز النص للنطق. */
object Voices {
    const val GOOGLE_TTS = "com.google.android.tts"

    fun create(c: Context, listener: TextToSpeech.OnInitListener): TextToSpeech {
        return try {
            c.packageManager.getPackageInfo(GOOGLE_TTS, 0)
            TextToSpeech(c, listener, GOOGLE_TTS)
        } catch (_: Exception) {
            TextToSpeech(c, listener)
        }
    }

    fun arabic(engine: TextToSpeech): List<Voice> = try {
        engine.voices.orEmpty()
            .filter { it.locale.language == "ar" }
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }
                .thenByDescending { if (it.isNetworkConnectionRequired) 1 else 0 }
                .thenBy { it.name })
    } catch (_: Exception) {
        emptyList()
    }

    /** يضبط الصوت المختار أو الأفضل تلقائياً. يرجع true إذا وُجد صوت عربي. */
    fun apply(c: Context, engine: TextToSpeech): Boolean {
        val list = arabic(engine)
        val wanted = Store.voice(c)
        val pick = list.firstOrNull { it.name == wanted } ?: list.firstOrNull()
        return try {
            if (pick != null) {
                if (engine.voice?.name != pick.name) engine.voice = pick
                true
            } else {
                val r = engine.setLanguage(java.util.Locale("ar"))
                r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        } catch (_: Exception) {
            false
        }
    }

    fun label(v: Voice, index: Int): String {
        val q = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "ممتاز"
            v.quality >= Voice.QUALITY_HIGH -> "عالي"
            else -> "عادي"
        }
        val net = if (v.isNetworkConnectionRequired) "يحتاج إنترنت" else "بدون إنترنت"
        return "صوت ${index + 1} · ${v.locale.displayCountry} · $q · $net"
    }

    /** حروف عراقية/فارسية لا تنطقها محركات العربية بشكل صحيح. */
    fun forSpeech(text: String): String = text
        .replace("چ", "تش")
        .replace("گ", "ك")
        .replace("پ", "ب")
        .replace("ڤ", "ف")
        .replace("…", "، ")
        .replace(Regex("[«»\"“”]"), "")
}
