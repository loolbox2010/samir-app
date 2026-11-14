package iq.samir.friend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrainException(message: String) : Exception(message)

/** يرسل المحادثة إلى OpenRouter ويرجع رد الصديق. يُستدعى من خيط خلفي فقط. */
object Brain {
    private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"

    private fun systemPrompt(c: Context): String {
        val wake = Store.wake(c)
        val user = Store.userName(c)
        val now = try {
            SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Locale("ar")).format(Date())
        } catch (_: Exception) {
            ""
        }
        return listOf(
            "أنت «$wake»، صديق ذكي يتحدث مع المستخدم بالصوت على هاتفه.",
            if (user.isNotEmpty()) "اسم المستخدم: $user. ناده باسمه أحياناً بشكل طبيعي."
            else "لا تعرف اسم المستخدم بعد؛ يمكنك أن تسأله عنه مرة واحدة بلطف.",
            "تحدث بالعربية بأسلوب ودود وقريب، وطابق لهجة المستخدم (غالباً عراقية).",
            "ردودك قصيرة وطبيعية مثل حديث بين صديقين، من جملتين إلى أربع جمل، لأنها تُقرأ بصوت مسموع. أطل فقط إذا طلب شرحاً مفصلاً.",
            "اكتب نصاً عادياً بلا رموز تنسيق ولا نجوم ولا قوائم ولا رموز تعبيرية.",
            "كلام المستخدم يصلك من التعرف على الصوت وقد يحتوي أخطاء؛ افهم المقصود.",
            "استمع جيداً، اسأل أسئلة متابعة، وساعده بمعلومات دقيقة ونصائح عملية في أي موضوع.",
            "كن صادقاً: إذا لم تكن متأكداً فقل ذلك، وخالفه الرأي بلطف عندما ترى أنه مخطئ.",
            "أنت ذكاء اصطناعي ولا تدّعي أنك إنسان. اهتم به بصدق وشجّعه على التواصل مع أهله وأصدقائه.",
            if (now.isNotEmpty()) "الوقت الآن عند المستخدم: $now." else "",
            "لا تتوفر لك الأخبار الحالية؛ قل ذلك إن سُئلت."
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun ask(c: Context): String {
        val key = Store.apiKey(c)
        if (key.isEmpty()) throw BrainException("أدخل مفتاح OpenRouter من الإعدادات داخل التطبيق.")

        val hist = Store.history(c).takeLast(24).toMutableList()
        while (hist.isNotEmpty() && hist[0].role != "user") hist.removeAt(0)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(c)))
        hist.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val body = JSONObject()
            .put("model", Store.model(c))
            .put("max_tokens", 500)
            .put("messages", messages)
            .toString()

        val conn = (URL(API_URL).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("X-Title", "Samir")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw BrainException(
                    when (code) {
                        401 -> "مفتاح OpenRouter غير صحيح، راجعه في الإعدادات."
                        402 -> "رصيد OpenRouter لا يكفي."
                        400, 404 -> "اسم النموذج غير صحيح، عدّله في الإعدادات."
                        429 -> "وصلت لحد الرسائل المسموح هسه، حاول بعد شوية."
                        else -> "صار خطأ بالخدمة، حاول مرة ثانية."
                    }
                )
            }

            val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val reply = if (msg.isNull("content")) "" else msg.optString("content")
            val clean = reply.replace(Regex("[*#_`]"), "").trim()
            if (clean.isEmpty()) throw BrainException("ما وصلني رد واضح، ممكن تعيد؟")
            return clean
        } catch (e: BrainException) {
            throw e
        } catch (e: IOException) {
            throw BrainException("ما أكدر أتصل بالإنترنت هسه.")
        } catch (e: Exception) {
            throw BrainException("صار خطأ بقراءة الرد، حاول مرة ثانية.")
        } finally {
            conn.disconnect()
        }
    }
}
