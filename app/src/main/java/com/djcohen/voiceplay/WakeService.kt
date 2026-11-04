package com.djcohen.voiceplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener as SysListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Always-on listener (foreground service, microphone). Step 1: a small offline Vosk model waits for the wake phrase.
 * Step 2: Google's speech recognizer takes the command. Step 3: [Router] plays it. Works with the screen off or locked.
 */
class WakeService : Service() {
    companion object {
        const val TAG = "VoicePlay"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "listening_quiet"
        private const val NOTIF_ID = 1
        @Volatile var running = false

        fun wakePhrase(ctx: Context) = ctx.getSharedPreferences("voiceplay", MODE_PRIVATE).getString("wake", "hey music")!!.lowercase().trim()
        fun defaultTarget(ctx: Context) =
            Target.valueOf(ctx.getSharedPreferences("voiceplay", MODE_PRIVATE).getString("default", Target.METROLIST.name)!!)
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var triggered = false
    private var generation = 0   // invalidates stale callbacks after a restart

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Test hook: `am startservice ... --es text "play x"` runs a command as if it had been spoken.
        intent?.getStringExtra("text")?.let { if (running) handle(it); return START_STICKY }
        if (running) return START_STICKY
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_MIN))
        startForeground(NOTIF_ID, notification("Say “${wakePhrase(this)}”"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceplay:listen").apply { acquire() }
        Thread {
            runCatching { model = Model(ModelStore.ensure(this)) }.onFailure { Log.e(TAG, "model load failed", it) }
            main.post { if (running) listenForWake() }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        speech?.shutdown(); speech = null
        recognizer?.destroy(); recognizer = null
        model?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jukebox").setContentText(text).setVisibility(Notification.VISIBILITY_SECRET).setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).build()
    }

    private fun status(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    // ---------------------------------------------------------------- step 1: wake phrase

    private fun listenForWake() {
        val m = model ?: run { status("Couldn’t load the wake-word model"); return }
        val phrase = wakePhrase(this)
        triggered = false
        val rec = Recognizer(m, 16000f, "[\"$phrase\", \"[unk]\"]")
        val gen = ++generation
        speech = SpeechService(rec, 16000f).also { s ->
            s.startListening(object : RecognitionListener {
                private fun check(json: String) {
                    if (gen != generation || triggered) return
                    val text = runCatching { JSONObject(json).let { it.optString("partial", it.optString("text")) } }.getOrDefault("")
                    if (phrase in text) { triggered = true; onWake() }
                }
                override fun onPartialResult(h: String?) { if (h != null) check(h) }
                override fun onResult(h: String?) { if (h != null) check(h) }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { Log.e(TAG, "vosk error", e) }
                override fun onTimeout() {}
            })
        }
        status("Say “$phrase”")
    }

    private fun onWake() {
        Log.i(TAG, "wake phrase heard")
        speech?.stop(); speech?.shutdown(); speech = null      // release the mic for the speech recognizer
        beep(ToneGenerator.TONE_PROP_BEEP)
        status("Listening…")
        main.postDelayed({ listenForCommand() }, 250)
    }

    // ---------------------------------------------------------------- step 2: the command

    private fun listenForCommand() {
        val gen = generation
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : SysListener {
                override fun onResults(r: Bundle?) {
                    if (gen != generation) return
                    handle(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(e: Int) { Log.w(TAG, "recognizer error $e"); fail("Didn’t catch that") }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    private fun handle(text: String) {
        Log.i(TAG, "heard: \"$text\"")
        val cmd = Parser.parse(text)
        if (cmd == null) { fail("Didn’t understand “$text”"); return }
        Router.run(this, cmd, defaultTarget(this), { status(it) }) { main.post { resume() } }
    }

    private fun fail(msg: String) {
        beep(ToneGenerator.TONE_PROP_NACK); status(msg); resume()
    }

    private fun resume() {
        recognizer?.destroy(); recognizer = null
        if (running) main.postDelayed({ if (running) listenForWake() }, 800)
    }

    private fun beep(tone: Int) {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(tone, 150) }
    }
}
