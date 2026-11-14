package iq.samir.friend

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجل الكلام: كل ما يسمعه سمير يُحفظ نصاً في ملف لكل يوم داخل الهاتف فقط.
 * الصيغة: "HH:mm | النص"
 */
object Journal {
    private val lock = Any()

    private fun dir(c: Context): File = File(c.filesDir, "journal").apply { mkdirs() }
    private fun dayName(d: Date = Date()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    private fun time(d: Date = Date()) = SimpleDateFormat("HH:mm", Locale.US).format(d)

    fun add(c: Context, text: String) {
        val t = text.replace("\n", " ").trim()
        if (t.length < 2) return
        synchronized(lock) {
            try {
                File(dir(c), dayName() + ".txt").appendText("${time()} | $t\n", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    private fun days(c: Context): List<File> =
        dir(c).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: emptyList()

    fun today(c: Context): String = synchronized(lock) {
        try {
            File(dir(c), dayName() + ".txt").takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun todayCount(c: Context): Int = today(c).lines().count { it.isNotBlank() }

    fun clear(c: Context) = synchronized(lock) { dir(c).deleteRecursively(); Unit }

    private fun norm(s: String) = s
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .lowercase(Locale.ROOT)

    private val stop = setOf(
        "شنو", "شلون", "وين", "متى", "ليش", "اليوم", "امس", "البارحه", "هذا", "هذه", "هاي",
        "كان", "كانت", "على", "الي", "اللي", "الذي", "التي", "قال", "قالت", "احكي", "حچي", "احچي",
        "سمير", "عن", "من", "في", "ويا", "مع", "شي", "شيء"
    )

    /** نص السجل الذي يُرسل مع السؤال: آخر يومين كاملين + أسطر أقدم تحتوي كلمات السؤال. */
    fun context(c: Context, question: String): String = synchronized(lock) {
        try {
            val files = days(c)
            if (files.isEmpty()) return@synchronized ""

            val recent = files.take(2).reversed().joinToString("\n") {
                "[${it.nameWithoutExtension}]\n" + it.readText(Charsets.UTF_8)
            }.takeLast(12000)

            val words = norm(question).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && it !in stop }
                .map { if (it.startsWith("ال") && it.length > 4) it.substring(2) else it }
                .toSet()

            val older = StringBuilder()
            if (words.isNotEmpty()) {
                val hits = mutableListOf<String>()
                for (f in files.drop(2).take(90)) {
                    for (line in f.readLines(Charsets.UTF_8)) {
                        val n = norm(line)
                        if (words.any { n.contains(it) }) hits.add("${f.nameWithoutExtension} $line")
                    }
                }
                if (hits.isNotEmpty()) {
                    older.append("مقتطفات أقدم فيها كلمات من السؤال:\n")
                    older.append(hits.takeLast(80).joinToString("\n").takeLast(6000))
                    older.append("\n\n")
                }
            }
            older.toString() + "آخر ما سُمع (أحدث شيء في الأسفل):\n" + recent
        } catch (_: Exception) {
            ""
        }
    }
}
