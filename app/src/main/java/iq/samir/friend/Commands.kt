package iq.samir.friend

import java.util.Locale

/** فهم أوامر الهاتف من الكلام (بدون ذكاء اصطناعي، مجاناً وبسرعة). */
object Commands {

    sealed class Cmd {
        data class Call(val who: String) : Cmd()
        data class Sms(val who: String, val text: String) : Cmd()
        data class WhatsApp(val who: String, val text: String) : Cmd()
        object CallLog : Cmd()
        object EndCall : Cmd()
    }

    fun norm(s: String): String = s
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace('ؤ', 'و')
        .replace('ئ', 'ي')
        .replace(Regex("[.,،؛؟!?]"), " ")
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
        .trim()

    private const val A = "[اأإآ]"

    private fun cleanWho(s: String): String = s
        .replace(Regex("[.,،؛؟!?]"), " ")
        .replace(Regex("\\s+(هسه|هسا|الحين|الان|الآن|رجاء|رجاءً|لو سمحت|بسرعه|بسرعة)\\s*$"), "")
        .trim()

    private val messageSplit = Regex(
        "\\s*(?::|،|\\s(?:يقول|تقول|يكول|تكول|مضمونها|نصها|وقل له|وقله|قل له|قله|كله|وكله|وكل له|بانه|بأنه|انه|إنه|أنه)\\s)\\s*"
    )

    private fun whoAndText(rest: String): Pair<String, String>? {
        val m = messageSplit.find(rest) ?: return null
        val who = cleanWho(rest.substring(0, m.range.first))
        val text = rest.substring(m.range.last + 1).trim()
        if (who.isEmpty() || text.isEmpty()) return null
        return who to text
    }

    fun parse(original: String): Cmd? {
        val t = original.trim()
        val n = norm(t)

        // إنهاء المكالمة
        if (Regex("(انه|انهي|اغلق|سد|سكر|اقطع|بند)\\s*(ال)?(مكالمه|خط|اتصال)").containsMatchIn(n)) return Cmd.EndCall

        // سجل المكالمات
        if (Regex("(منو|من|مين|شنو|شو|اي)\\s+(اتصل|اتصلوا|خابر|دق|رن)").containsMatchIn(n) ||
            n.contains("سجل المكالمات") || Regex("المكالمات\\s*(الفايته|الفائته|اليوم|الاخيره)").containsMatchIn(n) ||
            n.contains("مكالمات فايته") || n.contains("مكالمات فائته")
        ) return Cmd.CallLog

        // واتساب
        if (Regex("(واتساب|واتس اب|واتس|وتساب|whatsapp)").containsMatchIn(n)) {
            val m = Regex("(?:${A}رسل|دز|${A}بعث|${A}كتب|راسل)\\s+(?:رساله\\s+|رسالة\\s+)?(?:واتساب|واتس اب|واتس|وتساب|whatsapp)?\\s*(?:ل|${A}لى|على|ال)?\\s*(.+)")
                .find(t)
            if (m != null) {
                val rest = m.groupValues[1].replace(Regex("\\s*(?:ب|على|عبر|في)?\\s*(?:ال)?(?:واتساب|واتس اب|واتس|وتساب|whatsapp)\\s*"), " ").trim()
                whoAndText(rest)?.let { return Cmd.WhatsApp(it.first, it.second) }
            }
        }

        // رسالة نصية
        val sms = Regex("(?:^|\\s)(?:${A}رسل|دز|${A}بعث|راسل)\\s+(?:رساله|رسالة|مسج|مسچ)?\\s*(?:ل|${A}لى|على)?\\s*(.+)").find(t)
        if (sms != null) {
            whoAndText(sms.groupValues[1])?.let { return Cmd.Sms(it.first, it.second) }
        }

        // اتصال
        val call = Regex("(?:^|\\s)(?:${A}تصل|${A}تّصل|خابر)\\s+(?:ب|على|${A}لى|ل|ويا|مع)?\\s*(.+)").find(t)
            ?: Regex("(?:^|\\s)(?:دق|رن)\\s+(?:على|ل)\\s*(.+)").find(t)
        if (call != null) {
            val who = cleanWho(call.groupValues[1])
            if (who.isNotEmpty() && who.split(" ").size <= 4) return Cmd.Call(who)
        }
        return null
    }

    fun isYes(t: String): Boolean {
        val n = norm(t)
        return Regex("^(نعم|اي|ايه|ايوه|اكيد|تمام|صح|اوكي|اوك|يلا|يالله|موافق|بلي|بلى|اتصل|ارسل|دز|رد|ok|yes)(\\s|$)").containsMatchIn(n) ||
            n.contains("نعم") || n == "اي"
    }

    fun isNo(t: String): Boolean {
        val n = norm(t)
        return Regex("^(لا|كلا|لاء|الغي|الغ|خليها|خلي|اتركه|اتركها|بطل|بطلت|ما اريد|مااريد|no)(\\s|$)").containsMatchIn(n) ||
            n.contains("الغي") || n.contains("لا تتصل") || n.contains("لا ترسل")
    }

    /** رد على مكالمة واردة: "accept" أو "reject" مع رسالة اختيارية. */
    fun incoming(t: String): Pair<String, String?>? {
        val n = norm(t)
        if (Regex("(ارفض|رفض|سد|سكر|اغلق|لا ترد|ما ارد|لا|كلا|مشغول)").containsMatchIn(n)) {
            val m = Regex("(?:${A}رسل|دز|${A}بعث|قل|كل|كله|قله)\\s*(?:له|لها|لهم)?\\s*:?\\s*(.+)").find(t)
            val msg = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            return "reject" to msg
        }
        if (Regex("(رد|جاوب|اجب|اقبل|افتح|نعم|اي|ايه|تمام|اكيد)").containsMatchIn(n)) return "accept" to null
        return null
    }

    fun ordinal(t: String): Int? {
        val n = norm(t)
        return when {
            Regex("(الاول|الاولى|واحد|^1$|رقم 1)").containsMatchIn(n) -> 0
            Regex("(الثاني|الثانيه|التاني|ثنين|اثنين|^2$|رقم 2)").containsMatchIn(n) -> 1
            Regex("(الثالث|الثالثه|التالت|ثلاثه|^3$|رقم 3)").containsMatchIn(n) -> 2
            Regex("(الرابع|الرابعه|اربعه|^4$|رقم 4)").containsMatchIn(n) -> 3
            else -> null
        }
    }
}
