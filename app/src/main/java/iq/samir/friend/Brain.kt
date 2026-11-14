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

    private fun systemPrompt(c: Context, web: Boolean, question: String): String {
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
            "تحدث بأسلوب ودود وقريب، لكن بعربية بسيطة وواضحة قريبة من الفصحى (لهجة بيضاء يفهمها العراقي)، لأن ردودك تُقرأ بصوت آلي لا يجيد العامية. لا تستخدم الحروف چ وگ وپ ولا الكلمات العامية الثقيلة، واكتب جملاً قصيرة مع علامات ترقيم واضحة.",
            "ردودك قصيرة وطبيعية مثل حديث بين صديقين، من جملتين إلى أربع جمل، لأنها تُقرأ بصوت مسموع. أطل فقط إذا طلب شرحاً مفصلاً.",
            "اكتب نصاً عادياً بلا رموز تنسيق ولا نجوم ولا قوائم ولا روابط ولا رموز تعبيرية.",
            "كلام المستخدم يصلك من التعرف على الصوت وقد يحتوي أخطاء؛ افهم المقصود.",
            "استمع جيداً، اسأل أسئلة متابعة، وساعده بمعلومات دقيقة ونصائح عملية في أي موضوع.",
            "كن صادقاً: إذا لم تكن متأكداً فقل ذلك، وخالفه الرأي بلطف عندما ترى أنه مخطئ.",
            "أنت ذكاء اصطناعي ولا تدّعي أنك إنسان. اهتم به بصدق وشجّعه على التواصل مع أهله وأصدقائه.",
            if (now.isNotEmpty()) "الوقت الآن عند المستخدم: $now. المستخدم في العراق." else "",
            if (web) "عندك أداة بحث في الإنترنت. استخدمها لكل ما يتغير مع الوقت: الأخبار، الأسعار، الطقس، المباريات، المناصب، وأي معلومة لست متأكداً منها. لا تستخدمها للأحاديث العادية. اذكر المصدر باسمه فقط (مثل: حسب الجزيرة) بلا روابط."
            else "لا يمكنك البحث في الإنترنت الآن؛ إذا سُئلت عن أخبار أو معلومات حديثة فقل إنك لا تملك معلومات محدثة.",
            memoryBlock(c, question)
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun memoryBlock(c: Context, question: String): String {
        if (!Store.memory(c)) return ""
        val log = Journal.context(c, question)
        if (log.isEmpty()) return ""
        return "\nأنت تستمع لما يدور حول المستخدم وتحفظه نصاً. هذا سجل ما سمعته (محادثاته مع الآخرين)، مكتوب آلياً من التعرف على الصوت، وقد يحتوي أخطاء، ولا يميّز من المتكلم. " +
            "استخدمه عندما يسأل المستخدم عن شيء قيل أو اتُّفق عليه أو يطلب ملخصاً، واذكر الوقت إن فاد. لا تخترع ما ليس في السجل؛ إذا لم تجد الجواب فقل ذلك. لا تذكر السجل إذا لم يكن له علاقة بالسؤال.\n" +
            "----- بداية السجل -----\n$log\n----- نهاية السجل -----"
    }

    private fun buildBody(c: Context, web: Boolean): String {
        val hist = Store.history(c).takeLast(24).toMutableList()
        while (hist.isNotEmpty() && hist[0].role != "user") hist.removeAt(0)

        val question = hist.lastOrNull { it.role == "user" }?.content.orEmpty()
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt(c, web, question)))
        hist.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val body = JSONObject()
            .put("model", Store.model(c))
            .put("max_tokens", 800)
            .put("messages", messages)

        if (web) {
            body.put(
                "tools", JSONArray().put(
                    JSONObject()
                        .put("type", "openrouter:web_search")
                        .put("parameters", JSONObject().put("max_results", 3))
                )
            )
        }
        return body.toString()
    }

    private fun post(key: String, body: String): Pair<Int, String> {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("X-Title", "Samir")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun clean(reply: String): String = reply
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")   // روابط markdown
        .replace(Regex("https?://\\S+"), "")                   // روابط مكشوفة
        .replace(Regex("\\[\\d+\\]"), "")                      // أرقام المراجع [1]
        .replace(Regex("[*#_`]"), "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()

    fun ask(c: Context): String {
        val key = Store.apiKey(c)
        if (key.isEmpty()) throw BrainException("أدخل مفتاح OpenRouter من الإعدادات داخل التطبيق.")

        try {
            var web = Store.webSearch(c)
            var (code, text) = post(key, buildBody(c, web))

            // إذا فشل البحث (لا رصيد، أو النموذج لا يدعم الأدوات) نعيد السؤال بدون إنترنت
            if (web && code in listOf(400, 402, 404, 405, 422)) {
                web = false
                val retry = post(key, buildBody(c, false))
                code = retry.first
                text = retry.second
            }

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
            val out = clean(reply)
            if (out.isEmpty()) throw BrainException("ما وصلني رد واضح، ممكن تعيد؟")
            return out
        } catch (e: BrainException) {
            throw e
        } catch (e: IOException) {
            throw BrainException("ما أكدر أتصل بالإنترنت هسه.")
        } catch (e: Exception) {
            throw BrainException("صار خطأ بقراءة الرد، حاول مرة ثانية.")
        }
    }
}
