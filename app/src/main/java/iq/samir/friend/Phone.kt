package iq.samir.friend

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** جهات الاتصال، الاتصال، الرسائل، وسجل المكالمات. */
object Phone {

    data class Contact(val name: String, val number: String)

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS
    )

    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun variants(q: String): List<String> {
        val n = Commands.norm(q)
        val out = mutableListOf(n)
        if (n.length > 3 && (n.startsWith("ب") || n.startsWith("ل"))) out.add(n.substring(1))
        if (n.startsWith("ال") && n.length > 4) out.add(n.substring(2))
        return out.distinct()
    }

    fun findContacts(c: Context, query: String): List<Contact> {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        val qs = variants(query)
        val scored = mutableListOf<Pair<Int, Contact>>()
        try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val number = cur.getString(1) ?: continue
                    val nn = Commands.norm(name)
                    var best = 0
                    for (q in qs) {
                        val s = when {
                            nn == q -> 4
                            nn.startsWith("$q ") || nn.startsWith(q) -> 3
                            q.startsWith(nn) && nn.length >= 3 -> 2
                            nn.contains(q) -> 2
                            q.split(" ").all { w -> w.length >= 2 && nn.contains(w) } -> 1
                            else -> 0
                        }
                        if (s > best) best = s
                    }
                    if (best > 0) scored.add(best to Contact(name, number))
                }
            }
        } catch (_: Exception) {
        }
        if (scored.isEmpty()) return emptyList()
        val top = scored.maxOf { it.first }
        return scored.filter { it.first == top }.map { it.second }
            .distinctBy { Commands.norm(it.name) }
            .take(4)
    }

    fun nameForNumber(c: Context, number: String): String? {
        if (number.isBlank() || !has(c, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    fun placeCall(c: Context, number: String): Boolean {
        if (!has(c, Manifest.permission.CALL_PHONE)) return false
        return try {
            val tm = c.getSystemService(TelecomManager::class.java)
            tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (_: Exception) {
            try {
                c.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun sendSms(c: Context, number: String, text: String): Boolean {
        if (!has(c, Manifest.permission.SEND_SMS)) return false
        return try {
            @Suppress("DEPRECATION")
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun accept(c: Context): Boolean {
        if (!has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            c.getSystemService(TelecomManager::class.java).acceptRingingCall()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun endCall(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            c.getSystemService(TelecomManager::class.java).endCall()
        } catch (_: Exception) {
            false
        }
    }

    /** رقم دولي لواتساب: 07xx → 9647xx */
    fun intl(number: String): String {
        var d = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (d.startsWith("00")) d = d.substring(2)
        if (d.startsWith("0")) d = "964" + d.substring(1)
        return d
    }

    fun whatsappIntent(number: String, text: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${intl(number)}&text=${Uri.encode(text)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun spellNumber(number: String): String = number.filter { it.isDigit() }.toCharArray().joinToString(" ")

    fun callLogToday(c: Context): String {
        if (!has(c, Manifest.permission.READ_CALL_LOG)) return "ما عندي إذن قراءة سجل المكالمات. افتح التطبيق واسمح به."
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fmt = SimpleDateFormat("h:mm a", Locale("ar"))
        val lines = mutableListOf<String>()
        try {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?", arrayOf(start.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cur ->
                while (cur.moveToNext() && lines.size < 8) {
                    val name = cur.getString(0)?.takeIf { it.isNotBlank() } ?: "رقم ${spellNumber(cur.getString(1) ?: "")}"
                    val type = when (cur.getInt(2)) {
                        CallLog.Calls.MISSED_TYPE -> "مكالمة فائتة من"
                        CallLog.Calls.INCOMING_TYPE -> "رددت على"
                        CallLog.Calls.OUTGOING_TYPE -> "اتصلت أنت بـ"
                        CallLog.Calls.REJECTED_TYPE -> "رفضت مكالمة"
                        else -> "مكالمة مع"
                    }
                    lines.add("$type $name الساعة ${fmt.format(Date(cur.getLong(3)))}")
                }
            }
        } catch (_: Exception) {
        }
        return if (lines.isEmpty()) "ما في مكالمات اليوم." else "مكالمات اليوم: " + lines.joinToString("، ") + "."
    }
}
