package com.djcohen.voiceplay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Opens straight into listening. Say "Hey Google, open Jukebox", then e.g. "play radiohead on metrolist",
 * "play jazz on AIMP", "pause" or "next". Tap the circle to listen again; tap the player name to change the default.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var defaultBtn: TextView
    private lateinit var alwaysBtn: TextView
    private lateinit var wakeField: android.widget.EditText
    private val prefs by lazy { getSharedPreferences("voiceplay", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    private var defaultTarget: Target
        get() = Target.valueOf(prefs.getString("default", Target.METROLIST.name)!!)
        set(v) { prefs.edit().putString("default", v.name).apply(); renderDefault() }

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); setStroke((2 * dp).toInt(), stroke); cornerRadius = 100 * dp }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amber = 0xFFF4B63F.toInt(); val cream = 0xFFF2E3C6.toInt()
        val box = JukeboxView(this).apply { setOnClickListener { retries = 1; startListening() } }
        status = TextView(this).apply {
            textSize = 20f; setTextColor(amber); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAllCaps = true; letterSpacing = 0.08f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { box.active = e.toString().let { it.startsWith("Listening") || it.startsWith("Thinking") } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        heard = TextView(this).apply { textSize = 14f; setTextColor(cream); gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE; maxLines = 2 }
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xFF1A0F0A.toInt()); setStroke((2 * dp).toInt(), amber); cornerRadius = 14 * dp }
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(heard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
        }
        defaultBtn = TextView(this).apply {
            textSize = 14f; setTextColor(cream); gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = pill(0x33F2E3C6, cream)
            setOnClickListener { defaultTarget = if (defaultTarget == Target.METROLIST) Target.AIMP else Target.METROLIST }
        }
        alwaysBtn = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { toggleAlways() }
        }
        wakeField = android.widget.EditText(this).apply {
            setText(prefs.getString("wake", "hey music")); setTextColor(cream); setHintTextColor(0x88FFFFFF.toInt())
            hint = "wake phrase"; gravity = Gravity.CENTER; setSingleLine(); textSize = 14f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { prefs.edit().putString("wake", e.toString().lowercase().trim()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B2F4F.toInt(), 0xFF0A1424.toInt()))
            addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(display, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(defaultBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() })
                addView(alwaysBtn)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
            addView(TextView(context).apply { text = "Wake phrase (restart listening after changing)"; textSize = 11f; setTextColor(0x88F2E3C6.toInt()); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
            addView(wakeField, LinearLayout.LayoutParams((220 * dp).toInt(), -2))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + (20 * dp).toInt(), b.top + (8 * dp).toInt(), b.right + (20 * dp).toInt(), b.bottom + (12 * dp).toInt()); ins
            }
        }
        setContentView(root)
        renderDefault()
        renderAlways()
    }

    private var handledAt = 0L
    private var retries = 0
    private val startRunnable = Runnable { startListening() }
    private fun scheduleListen(delay: Long) { main.removeCallbacks(startRunnable); main.postDelayed(startRunnable, delay) }

    override fun onResume() { super.onResume(); kick() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); kick() }

    /** Typed text (`--es text "play x on aimp"`) skips the microphone; otherwise start listening. */
    private fun kick() {
        val typed = intent.getStringExtra("text")
        intent.removeExtra("text")
        if (intent.getBooleanExtra("listen", false)) {   // `--ez listen true` starts always-listening mode
            intent.removeExtra("listen")
            startForegroundService(Intent(this, WakeService::class.java))
            main.postDelayed({ renderAlways() }, 600)
            return
        }
        if (intent.getStringExtra("svctext") != null) {  // `--es svctext "play x"` feeds the running service (test hook)
            startService(Intent(this, WakeService::class.java).putExtra("text", intent.getStringExtra("svctext")))
            intent.removeExtra("svctext"); return
        }
        if (typed != null) { handledAt = System.currentTimeMillis(); main.post { handle(typed) } }
        // Short delay so Gemini has released the microphone after launching us.
        else if (System.currentTimeMillis() - handledAt > 2000) { retries = 3; scheduleListen(900) }
    }

    override fun onPause() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        super.onPause()
    }

    private fun renderAlways() {
        val on = WakeService.running
        alwaysBtn.text = if (on) "Always listening: ON" else "Always listening: OFF"
        alwaysBtn.setTextColor(if (on) 0xFF0D1626.toInt() else 0xFFF2E3C6.toInt())
        alwaysBtn.background = pill(if (on) 0xFF3DB8A8.toInt() else 0x33F2E3C6, if (on) 0xFF3DB8A8.toInt() else 0xFFF2E3C6.toInt())
    }

    private fun toggleAlways() {
        if (WakeService.running) {
            startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
            main.postDelayed({ renderAlways() }, 400)
        } else {
            val need = listOf(Manifest.permission.RECORD_AUDIO)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 2); return }
            startForegroundService(Intent(this, WakeService::class.java))
            main.postDelayed({ renderAlways() }, 600)
        }
    }

    private fun renderDefault() { defaultBtn.text = "Player: ${defaultTarget.label}" }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow the microphone"; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "No speech recognition on this phone"; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening…"; heard.text = "e.g. “play Radiohead on Metrolist”" }
                override fun onResults(r: Bundle?) = handle(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                override fun onError(e: Int) {
                    // Busy/audio errors usually mean Gemini hasn't released the mic yet; no-speech/no-match just needs another go.
                    if (retries > 0 && e in listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT,
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) {
                        retries--; status.text = "Starting…"; scheduleListen(500); return
                    }
                    status.text = "Didn’t catch that"; heard.text = "Tap the circle to try again"
                }
                override fun onPartialResults(p: Bundle?) { heard.text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "Thinking…" }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    private fun handle(text: String) {
        heard.text = "“$text”"
        val cmd = Parser.parse(text)
        if (cmd == null) { status.text = "Didn’t understand"; return }
        Router.run(this, cmd, defaultTarget, { status.text = it }) { main.postDelayed({ finish() }, 300) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 2) { toggleAlways(); return }
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status.text = "Microphone permission is needed"
    }
}
