package iq.samir.friend

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val accent = Color.parseColor("#0D7A68")
    private val bg = Color.parseColor("#EEF2F0")
    private val surface = Color.WHITE
    private val ink = Color.parseColor("#13211E")
    private val muted = Color.parseColor("#5D6F6A")
    private val line = Color.parseColor("#D5DEDB")
    private val warn = Color.parseColor("#B4472B")

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var settingsBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var batteryBtn: Button

    private var pendingAction: (() -> Unit)? = null
    private var previewTts: TextToSpeech? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            when (key) {
                Store.K_STATUS -> renderStatus()
                Store.K_HISTORY -> renderChat()
                Store.K_RUNNING -> renderToggle()
                Store.K_WAKE -> renderTitle()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(if (filled) Color.WHITE else accent)
        background = if (filled) rounded(accent, 14) else rounded(Color.TRANSPARENT, 14, accent)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun EditText.onChange(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = accent

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        // العنوان والحالة
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
        }
        names.addView(titleView)
        names.addView(statusView)
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("الإعدادات", false) {
            settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        root.addView(header)

        // زر التشغيل
        toggleBtn = button("", true) { onToggle() }
        toggleBtn.textSize = 18f
        root.addView(toggleBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        })

        // الإعدادات
        settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 14, line)
            setPadding(dp(14), dp(10), dp(14), dp(14))
            visibility = View.GONE
        }
        buildSettings()
        val settingsScroll = ScrollView(this).apply { addView(settingsBox) }
        root.addView(settingsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // المحادثة
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll = ScrollView(this).apply { addView(chatBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // الكتابة
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "اكتب رسالتك هنا"
            textSize = 16f
            setTextColor(ink)
            background = rounded(surface, 22, line)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(button("إرسال", true) { onSend() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
        ).apply { marginStart = dp(8) })
        root.addView(composer)

        setContentView(root)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(muted)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun field(title: String, value: String, password: Boolean = false, ltr: Boolean = false,
                      hint: String = "", onChange: (String) -> Unit): EditText {
        settingsBox.addView(label(title))
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 16f
            setTextColor(ink)
            setSingleLine(true)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
        }
        e.onChange(onChange)
        settingsBox.addView(e)
        return e
    }

    private fun buildSettings() {
        field("مفتاح OpenRouter", Store.apiKey(this), password = true, ltr = true, hint = "sk-or-...") {
            Store.put(this, Store.K_API, it.trim())
        }
        field("النموذج", Store.model(this), ltr = true, hint = Store.DEFAULT_MODEL) {
            Store.put(this, Store.K_MODEL, it.trim())
        }
        field("اسم الصديق (كلمة النداء)", Store.wake(this)) {
            Store.put(this, Store.K_WAKE, it.trim())
        }
        field("اسمك", Store.userName(this), hint = "حتى يناديك باسمك") {
            Store.put(this, Store.K_USER, it.trim())
        }

        val voiceBtn = button("اختر صوت سمير", false) { pickVoice() }
        settingsBox.addView(voiceBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val ttsSettings = button("تثبيت أصوات عربية أوضح", false) { openTtsSettings() }
        settingsBox.addView(ttsSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val memory = CheckBox(this).apply {
            text = "وضع الذاكرة: احفظ كل كلام يسمعه نصاً على الهاتف"
            textSize = 15f
            setTextColor(ink)
            isChecked = Store.memory(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.putBool(this@MainActivity, Store.K_MEMORY, checked) }
        }
        settingsBox.addView(memory, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val showLog = button("عرض سجل اليوم", false) {
            val text = Journal.today(this).ifBlank { "لا يوجد كلام محفوظ اليوم." }
            AlertDialog.Builder(this)
                .setTitle("سجل اليوم")
                .setMessage(text)
                .setPositiveButton("إغلاق", null)
                .show()
        }
        settingsBox.addView(showLog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val clearLog = button("امسح كل السجل", false) {
            AlertDialog.Builder(this)
                .setTitle("مسح السجل")
                .setMessage("سيُحذف كل الكلام المحفوظ نهائياً من الهاتف. متأكد؟")
                .setPositiveButton("امسح") { _, _ ->
                    Journal.clear(this)
                    Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        clearLog.setTextColor(warn)
        clearLog.background = rounded(Color.TRANSPARENT, 14, warn)
        settingsBox.addView(clearLog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val web = CheckBox(this).apply {
            text = "البحث في الإنترنت (أخبار، أسعار، طقس… يحتاج رصيداً في OpenRouter)"
            textSize = 15f
            setTextColor(ink)
            isChecked = Store.webSearch(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.putBool(this@MainActivity, Store.K_WEB, checked) }
        }
        settingsBox.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val mute = CheckBox(this).apply {
            text = "إخفاء نغمة الميكروفون (يكتم صوت الوسائط أثناء الانتظار)"
            textSize = 15f
            setTextColor(ink)
            isChecked = Store.muteBeep(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.putBool(this@MainActivity, Store.K_MUTE, checked) }
        }
        settingsBox.addView(mute, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        batteryBtn = button("اسمح له بالعمل في الخلفية دائماً", false) { askBattery() }
        settingsBox.addView(batteryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val clear = button("امسح المحادثة", false) {
            Store.clearHistory(this)
            Toast.makeText(this, "تم مسح المحادثة", Toast.LENGTH_SHORT).show()
        }
        clear.setTextColor(warn)
        clear.background = rounded(Color.TRANSPARENT, 14, warn)
        settingsBox.addView(clear, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        settingsBox.addView(label("الإعدادات والمحادثة محفوظة على هذا الهاتف فقط."))
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        if (Store.apiKey(this).isEmpty()) settingsBox.visibility = View.VISIBLE
        renderTitle()
        renderStatus()
        renderToggle()
        renderChat()
        renderBattery()
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    // ---------------- العرض ----------------

    private fun renderTitle() {
        titleView.text = Store.wake(this)
    }

    private fun renderStatus() {
        statusView.text = if (AssistantService.isRunning) Store.status(this)
        else "متوقف. اضغط تشغيل ثم نادِ «${Store.wake(this)}»"
    }

    private fun renderToggle() {
        val on = AssistantService.isRunning
        toggleBtn.text = if (on) "إيقاف ${Store.wake(this)}" else "تشغيل ${Store.wake(this)}"
        toggleBtn.background = rounded(if (on) warn else accent, 14)
        renderStatus()
    }

    private fun renderBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryBtn.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun renderChat() {
        chatBox.removeAllViews()
        val history = Store.history(this)
        if (history.isEmpty()) {
            addBubble("assistant",
                "هلا بيك! آني ${Store.wake(this)}، صديقك. اضغط «تشغيل» وبعدها نادِني «${Store.wake(this)}» واحچي شتريد. أبقى أسمعك حتى لو طلعت من التطبيق.")
        } else {
            history.forEach { addBubble(it.role, it.content) }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(role: String, text: String) {
        val mine = role == "user"
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextColor(if (mine) Color.WHITE else ink)
            background = if (mine) rounded(accent, 18) else rounded(surface, 18, line)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(8)
        }
        chatBox.addView(tv, lp)
    }

    // ---------------- الأوامر ----------------

    private fun serviceIntent(action: String) = Intent(this, AssistantService::class.java).setAction(action)

    private fun onToggle() {
        if (AssistantService.isRunning) {
            startService(serviceIntent(AssistantService.ACTION_STOP))
        } else {
            withPermissions { startForegroundService(serviceIntent(AssistantService.ACTION_START)) }
        }
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        withPermissions {
            startForegroundService(
                serviceIntent(AssistantService.ACTION_ASK).putExtra(AssistantService.EXTRA_TEXT, text)
            )
        }
    }

    private fun withPermissions(action: () -> Unit) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            Toast.makeText(this, "لازم تسمح بالميكروفون حتى يسمعك", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- الصوت ----------------

    private fun pickVoice() {
        val existing = previewTts
        if (existing != null) {
            showVoices(existing)
            return
        }
        Toast.makeText(this, "جاري تحميل الأصوات…", Toast.LENGTH_SHORT).show()
        var engine: TextToSpeech? = null
        engine = Voices.create(this) { status ->
            runOnUiThread {
                val e = engine ?: return@runOnUiThread
                if (status == TextToSpeech.SUCCESS) {
                    previewTts = e
                    showVoices(e)
                } else {
                    Toast.makeText(this, "محرك النطق لا يعمل. ثبّت Google Speech Services.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showVoices(engine: TextToSpeech) {
        val list: List<Voice> = Voices.arabic(engine)
        if (list.isEmpty()) {
            Toast.makeText(this, "لا توجد أصوات عربية مثبتة. اضغط «تثبيت أصوات عربية أوضح».", Toast.LENGTH_LONG).show()
            return
        }
        val current = Store.voice(this)
        val names = list.mapIndexed { i, v ->
            Voices.label(v, i) + if (v.name == current) "  ✓" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر صوتاً (يُسمعك مثالاً عند الضغط)")
            .setItems(names) { _, which ->
                val v = list[which]
                Store.put(this, Store.K_VOICE, v.name)
                try {
                    engine.voice = v
                    engine.setSpeechRate(0.95f)
                    engine.speak(
                        "هلا بيك، أنا ${Store.wake(this)}. هذا صوتي الجديد، هل هو واضح؟",
                        TextToSpeech.QUEUE_FLUSH, null, "preview"
                    )
                } catch (_: Exception) {
                }
                Toast.makeText(this, "تم اختيار " + Voices.label(v, which), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun openTtsSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(Voices.GOOGLE_TTS),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Voices.GOOGLE_TTS))
        )
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "اختر محرك Google ثم ثبّت صوت العربية", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "افتح إعدادات الهاتف ← تحويل النص إلى كلام", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try {
            previewTts?.shutdown()
        } catch (_: Exception) {
        }
        previewTts = null
        super.onDestroy()
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "افتح إعدادات البطارية واختر «بلا قيود» لهذا التطبيق", Toast.LENGTH_LONG).show()
            }
        }
    }
}
