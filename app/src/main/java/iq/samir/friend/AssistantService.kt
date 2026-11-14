package iq.samir.friend

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * خدمة تعمل في الخلفية: تنتظر كلمة النداء، تسمع السؤال، ترسله للذكاء الاصطناعي، وتقرأ الرد.
 */
class AssistantService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "iq.samir.friend.START"
        const val ACTION_STOP = "iq.samir.friend.STOP"
        const val ACTION_ASK = "iq.samir.friend.ASK"
        const val EXTRA_TEXT = "text"
        const val ACTION_END_CALL = "iq.samir.friend.END_CALL"
        private const val CHANNEL_ALERT = "samir_alerts"

        private const val CHANNEL = "samir_listen"
        private const val NOTIF_ID = 7
        private const val FOLLOW_MS = 10_000L

        @Volatile
        var isRunning = false
            private set
    }

    private enum class Mode { IDLE, WAKE, CAPTURE, THINKING, SPEAKING, IN_CALL }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var audio: AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mode = Mode.IDLE
    private var listening = false
    private var captureUntil = 0L
    private var muted = false
    private var errorStreak = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val listenRunnable = Runnable {
        if (mode == Mode.CAPTURE && System.currentTimeMillis() > captureUntil) {
            pending = null
            setMode(Mode.WAKE, wakeStatus())
        }
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "الاستماع", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "تنبيهات سمير", NotificationManager.IMPORTANCE_HIGH)
        )
        tts = Voices.create(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_END_CALL) {
            Phone.endCall(this)
            if (isRunning) return START_STICKY
        }

        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            acquireWakeLock()
            Store.putBool(this, Store.K_RUNNING, true)
            enterWake()
        }
        registerPhone()

        if (intent?.action == ACTION_ASK) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
            if (text.isNotEmpty()) ask(text)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterPhone()
        shutdown()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------- الإشعار ----------------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notify), "إيقاف", stop
        ).build()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(Store.wake(this))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(stopAction)
        if (mode == Mode.IN_CALL) {
            val end = PendingIntent.getService(
                this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_END_CALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify), "إنهاء المكالمة", end).build()
            )
        }
        return builder.build()
    }

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification(Store.status(this))
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            Store.setStatus(this, "تعذر التشغيل في الخلفية. افتح التطبيق واضغط تشغيل.")
            false
        }
    }

    private fun setMode(m: Mode, status: String) {
        mode = m
        Store.setStatus(this, status)
        if (isRunning) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
            } catch (_: Exception) {
            }
        }
    }

    private fun wakeStatus(): String =
        if (Store.memory(this)) "● يحفظ ما يسمعه (${Journal.todayCount(this)} اليوم) · نادِني «${Store.wake(this)}»"
        else "نادِني «${Store.wake(this)}» وأسمعك"

    // ---------------- الطاقة والصوت ----------------

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "samir:listen").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    /** يكتم صوت الوسائط أثناء الانتظار حتى لا تُسمع نغمة الميكروفون كل بضع ثوانٍ. */
    private fun mute(on: Boolean) {
        if (on == muted) return
        // نغمة الميكروفون تُعزف على قناة مختلفة حسب الهاتف، فنكتمها كلها أثناء الانتظار
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM
        )
        for (s in streams) {
            try {
                audio.adjustStreamVolume(s, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        muted = on
    }

    private fun shutdown() {
        val wasRunning = isRunning
        isRunning = false
        main.removeCallbacksAndMessages(null)
        stopListening()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        mute(false)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        mode = Mode.IDLE
        Store.putBool(this, Store.K_RUNNING, false)
        if (wasRunning) Store.setStatus(this, "متوقف")
    }

    // ---------------- الاستماع ----------------

    private fun enterWake() {
        setMode(Mode.WAKE, wakeStatus())
        scheduleListen(50)
    }

    private fun enterCapture(ms: Long = FOLLOW_MS) {
        captureUntil = System.currentTimeMillis() + ms
        setMode(Mode.CAPTURE, "أسمعك، تفضل احچي…")
        scheduleListen(50)
    }

    private fun scheduleListen(delay: Long) {
        main.removeCallbacks(listenRunnable)
        if (isRunning) main.postDelayed(listenRunnable, delay)
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Store.setStatus(this, "لا توجد خدمة تعرّف على الكلام. ثبّت أو حدّث تطبيق Google.")
            return false
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        return true
    }

    private fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
    }

    private fun startListening() {
        if (!isRunning || listening) return
        if (mode != Mode.WAKE && mode != Mode.CAPTURE) return
        if (!ensureRecognizer()) {
            scheduleListen(15000)
            return
        }
        if (Store.muteBeep(this)) mute(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-IQ")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-IQ")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(intent)
            listening = true
        } catch (e: Exception) {
            resetRecognizer()
            scheduleListen(1000)
        }
    }

    private fun stopListening() {
        main.removeCallbacks(listenRunnable)
        if (listening) {
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
        }
        listening = false
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onResults(results: Bundle?) {
        listening = false
        errorStreak = 0
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        handleHeard(list)
    }

    override fun onError(error: Int) {
        listening = false
        if (!isRunning) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Store.setStatus(this, "إذن الميكروفون مرفوض. افتح التطبيق واسمح به.")
                shutdown()
                stopSelf()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                resetRecognizer()
                scheduleListen(800)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                errorStreak++
                Store.setStatus(this, "لا يوجد اتصال، أحاول مرة ثانية…")
                scheduleListen(minOf(30000L, 2000L * errorStreak))
            }
            12, 13 -> { // اللغة غير مدعومة أو غير متوفرة
                Store.setStatus(this, "العربية غير متوفرة في خدمة الصوت. حدّث تطبيق Google.")
                scheduleListen(15000)
            }
            else -> scheduleListen(150) // صمت أو لم يُفهم الكلام: نعيد الاستماع
        }
    }

    // ---------------- فهم الكلام ----------------

    private fun norm(s: String): String = s
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace(Regex("[.,،؛؟!?]"), " ")
        .lowercase(Locale.ROOT)
        .trim()

    /** يرجع الكلام بعد كلمة النداء، أو null إذا لم يُذكر الاسم. */
    private fun findWake(text: String): String? {
        val wake = norm(Store.wake(this))
        if (wake.isEmpty()) return null
        val words = text.trim().split(Regex("\\s+"))
        for (i in words.indices) {
            if (norm(words[i]).contains(wake)) {
                return words.drop(i + 1).joinToString(" ").trim()
            }
        }
        return null
    }

    private fun handleHeard(results: List<String>) {
        when (mode) {
            Mode.CAPTURE -> {
                val t = results.firstOrNull()?.trim().orEmpty()
                if (t.isEmpty()) scheduleListen(100)
                else if (pending != null) handlePending(t)
                else ask(t)
            }
            Mode.WAKE -> {
                for (r in results) {
                    val rest = findWake(r)
                    if (rest != null) {
                        val words = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
                        if (words.size >= 2) ask(rest) else speak("نعم؟", "ack")
                        return
                    }
                }
                // لم يُنادَ: نحفظ الكلام في السجل بصمت
                if (Store.memory(this)) {
                    val heard = results.firstOrNull()?.trim().orEmpty()
                    if (heard.isNotEmpty()) {
                        Journal.add(this, heard)
                        setMode(Mode.WAKE, wakeStatus())
                    }
                }
                scheduleListen(100)
            }
            else -> {}
        }
    }

    // ---------------- السؤال والرد ----------------

    private fun ask(text: String) {
        if (mode == Mode.THINKING) return
        if (tryCommand(text)) return
        stopListening()
        setMode(Mode.THINKING, "أفكر بالرد…")
        Store.add(this, "user", text)
        val ctx = applicationContext
        worker.execute {
            var reply: String? = null
            var err: String? = null
            try {
                reply = Brain.ask(ctx)
            } catch (e: BrainException) {
                err = e.message
            } catch (e: Exception) {
                err = "ما وصل الرد. تأكد من الإنترنت."
            }
            main.post { onAnswer(reply, err) }
        }
    }

    private fun onAnswer(reply: String?, err: String?) {
        if (!isRunning) return
        if (reply != null) {
            Store.add(this, "assistant", reply)
            speak(reply, "reply")
        } else {
            Store.removeLastUser(this)
            speak(err ?: "صار خطأ، حاول مرة ثانية.", "error")
        }
    }

    // ---------------- أوامر الهاتف ----------------

    private sealed class Pending {
        class Confirm(val onYes: () -> Unit) : Pending()
        class Choose(val options: List<Phone.Contact>, val then: (Phone.Contact) -> Unit) : Pending()
        class Incoming(val number: String?) : Pending()
    }

    private var pending: Pending? = null
    private var phoneRegistered = false
    private var ringingNumber: String? = null
    private var announced = false

    private val announceRunnable = Runnable { announceIncoming() }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            val state = i?.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val num = i?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            main.post { onPhoneState(state, num) }
        }
    }

    private fun registerPhone() {
        if (phoneRegistered || !Phone.has(this, Manifest.permission.READ_PHONE_STATE)) return
        try {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(phoneReceiver, f)
            phoneRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterPhone() {
        if (!phoneRegistered) return
        try {
            unregisterReceiver(phoneReceiver)
        } catch (_: Exception) {
        }
        phoneRegistered = false
    }

    private fun onPhoneState(state: String?, number: String?) {
        if (!isRunning) return
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) ringingNumber = number
                if (!announced && Store.announceCalls(this)) {
                    main.removeCallbacks(announceRunnable)
                    main.postDelayed(announceRunnable, 800)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                main.removeCallbacks(announceRunnable)
                pending = null
                stopListening()
                try {
                    tts?.stop()
                } catch (_: Exception) {
                }
                mute(false)
                setMode(Mode.IN_CALL, "في مكالمة")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                main.removeCallbacks(announceRunnable)
                val wasBusy = mode == Mode.IN_CALL || announced || pending is Pending.Incoming
                ringingNumber = null
                announced = false
                if (pending is Pending.Incoming) pending = null
                if (wasBusy) {
                    mode = Mode.IDLE
                    main.postDelayed({ if (isRunning && mode == Mode.IDLE) enterWake() }, 1500)
                }
            }
        }
    }

    private fun announceIncoming() {
        if (!isRunning || announced) return
        announced = true
        val num = ringingNumber
        val name = num?.let { Phone.nameForNumber(this, it) }
            ?: num?.let { "رقم " + Phone.spellNumber(it) }
            ?: "رقم غير معروف"
        pending = Pending.Incoming(num)
        speak("يتصل بك $name. أرد؟", "incoming")
    }

    /** يرجع true إذا كان الكلام أمراً للهاتف وتم التعامل معه. */
    private fun tryCommand(text: String): Boolean {
        val cmd = Commands.parse(text) ?: return false
        // إذا لم يوجد الاسم في جهات الاتصال فالكلام غالباً ليس أمراً، نتركه للذكاء الاصطناعي
        val who = when (cmd) {
            is Commands.Cmd.Call -> cmd.who
            is Commands.Cmd.Sms -> cmd.who
            is Commands.Cmd.WhatsApp -> cmd.who
            else -> null
        }
        if (who != null && Phone.has(this, Manifest.permission.READ_CONTACTS) &&
            Phone.findContacts(this, who).isEmpty()
        ) return false
        Store.add(this, "user", text)
        when (cmd) {
            is Commands.Cmd.Call -> withContact(cmd.who) { c -> confirmThen("أتصل بـ ${c.name}؟", Store.confirmCalls(this)) { dial(c) } }
            is Commands.Cmd.Sms -> withContact(cmd.who) { c ->
                confirmThen("أرسل رسالة لـ ${c.name}: ${cmd.text}. أرسلها؟", true) {
                    if (Phone.sendSms(this, c.number, cmd.text)) say("أرسلت الرسالة لـ ${c.name}.")
                    else say("ما قدرت أرسل الرسالة. تأكد من إذن الرسائل.")
                }
            }
            is Commands.Cmd.WhatsApp -> withContact(cmd.who) { c -> openWhatsApp(c, cmd.text) }
            is Commands.Cmd.CallLog -> say(Phone.callLogToday(this))
            is Commands.Cmd.EndCall -> {
                if (Phone.endCall(this)) Store.add(this, "assistant", "أنهيت المكالمة.")
                else say("ما في مكالمة أنهيها، أو ما عندي إذن.")
            }
        }
        return true
    }

    private fun say(text: String, id: String = "done") {
        Store.add(this, "assistant", text)
        speak(text, id)
    }

    private fun withContact(who: String, then: (Phone.Contact) -> Unit) {
        if (!Phone.has(this, Manifest.permission.READ_CONTACTS)) {
            say("ما عندي إذن جهات الاتصال. افتح التطبيق واضغط تشغيل حتى تسمح به.")
            return
        }
        val list = Phone.findContacts(this, who)
        when {
            list.isEmpty() -> say("ما لقيت $who في جهات الاتصال.")
            list.size == 1 -> then(list[0])
            else -> {
                pending = Pending.Choose(list, then)
                val names = list.mapIndexed { i, c -> "${i + 1}، ${c.name}" }.joinToString(". ")
                say("عندك أكثر من واحد: $names. أيّ واحد؟", "choose")
            }
        }
    }

    private fun confirmThen(prompt: String, ask: Boolean, action: () -> Unit) {
        if (!ask) {
            action()
            return
        }
        pending = Pending.Confirm(action)
        say(prompt, "confirm")
    }

    private fun dial(c: Phone.Contact) {
        if (Phone.placeCall(this, c.number)) {
            Store.add(this, "assistant", "أتصل بـ ${c.name}…")
            stopListening()
            setMode(Mode.IN_CALL, "يتصل بـ ${c.name}")
        } else {
            say("ما قدرت أتصل. تأكد من إذن الاتصال.")
        }
    }

    private fun openWhatsApp(c: Phone.Contact, text: String) {
        val intent = Phone.whatsappIntent(c.number, text)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
        try {
            val pi = PendingIntent.getActivity(
                this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("رسالة واتساب لـ ${c.name}")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(11, n)
        } catch (_: Exception) {
        }
        say("جهزت رسالة واتساب لـ ${c.name}. افتحها واضغط إرسال.")
    }

    private fun handlePending(t: String) {
        when (val p = pending) {
            is Pending.Confirm -> when {
                Commands.isNo(t) -> { pending = null; say("تمام، ألغيت.") }
                Commands.isYes(t) -> { pending = null; p.onYes() }
                else -> speak("ما فهمت. قل نعم أو لا.", "confirm")
            }
            is Pending.Choose -> {
                if (Commands.isNo(t)) { pending = null; say("تمام، ألغيت."); return }
                val n = Commands.norm(t)
                val idx = Commands.ordinal(t)
                    ?: p.options.indexOfFirst { o -> Commands.norm(o.name).split(" ").any { w -> w.length >= 3 && n.contains(w) } }
                        .takeIf { it >= 0 }
                if (idx != null && idx < p.options.size) {
                    pending = null
                    p.then(p.options[idx])
                } else {
                    speak("ما فهمت. قل الأول أو الثاني.", "choose")
                }
            }
            is Pending.Incoming -> {
                val d = Commands.incoming(t)
                when (d?.first) {
                    "accept" -> {
                        pending = null
                        if (!Phone.accept(this)) say("ما قدرت أرد. تأكد من إذن المكالمات.")
                    }
                    "reject" -> {
                        pending = null
                        val ok = Phone.endCall(this)
                        val msg = d?.second
                        val num = p.number
                        if (ok && msg != null && num != null) {
                            if (Phone.sendSms(this, num, msg)) say("رفضت المكالمة وأرسلت له: $msg")
                            else say("رفضت المكالمة، لكن ما قدرت أرسل الرسالة.")
                        } else if (ok) {
                            Store.add(this, "assistant", "رفضت المكالمة.")
                        } else {
                            say("ما قدرت أرفض المكالمة.")
                        }
                    }
                    else -> scheduleListen(100)
                }
            }
            null -> ask(t)
        }
    }

    // ---------------- النطق ----------------

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Store.setStatus(this, "محرك النطق لا يعمل. ثبّت «Google Speech Services».")
            return
        }
        ttsReady = Voices.apply(this, engine)
        engine.setSpeechRate(0.95f)
        engine.setPitch(1.0f)
        if (!ttsReady) {
            Store.setStatus(this, "صوت العربية غير مثبت. ثبّته من إعدادات النطق في الهاتف.")
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { afterSpeech(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { afterSpeech(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { afterSpeech(utteranceId) }
            }
        })
    }

    private fun speak(text: String, id: String) {
        stopListening()
        mute(false)
        setMode(Mode.SPEAKING, "أتكلم…")
        val engine = tts
        if (engine == null || !ttsReady) {
            afterSpeech(id)
            return
        }
        Voices.apply(this, engine)
        val spoken = Voices.forSpeech(text)
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        val safe = if (spoken.length > max) spoken.substring(0, max) else spoken
        val res = engine.speak(safe, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (res != TextToSpeech.SUCCESS) afterSpeech(id)
    }

    private fun afterSpeech(id: String?) {
        if (!isRunning) return
        if (mode != Mode.SPEAKING && mode != Mode.THINKING) return
        when (id) {
            "ack", "reply", "confirm", "choose" -> enterCapture()
            "incoming" -> enterCapture(25_000L)
            else -> enterWake()
        }
    }
}
