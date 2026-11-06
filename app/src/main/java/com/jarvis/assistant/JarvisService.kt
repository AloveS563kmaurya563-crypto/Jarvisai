package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on foreground service: listens for "Hey Jarvis", sends the command to Claude, speaks the answer.
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var running = false

        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.jarvis.assistant.STOP"

        // "jarvis" as speech recognizers tend to write it, in English and Hindi
        private val WAKE = Regex(
            "(jarvis|jarwis|jarvish|jarviz|जार्विस|जारविस|जार्विश|जर्विस|ज़ार्विस|जारवि[सश]|जार्विज़)",
            RegexOption.IGNORE_CASE
        )
        private const val FOLLOW_UP_MS = 8000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var followUpUntil = 0L
    private lateinit var brain: Brain

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = Brain(applicationContext)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (!running) {
            running = true
            JarvisLog.add("🎙 Jarvis चालू — \"Hey Jarvis\" बोलिए")
            main.post { startListening() }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis सुन रहा है")
            .setContentText("\"Hey Jarvis\" बोलिए")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- listening ----------

    private fun startListening() {
        if (!running || busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisLog.add("❌ इस फ़ोन में speech recognition उपलब्ध नहीं (Google app / Speech Services चाहिए)")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            JarvisLog.add("mic error: ${e.message}")
            main.postDelayed({ resetRecognizer(); startListening() }, 1500)
        }
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running || busy) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    JarvisLog.add("❌ माइक की permission नहीं है")
                    stopSelf()
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    resetRecognizer()
                    main.postDelayed({ startListening() }, 800)
                }
                else -> main.postDelayed({ startListening() }, 250)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            handleSpeech(text)
        }
    }

    private fun handleSpeech(text: String) {
        if (text.isEmpty()) {
            startListening(); return
        }
        val now = System.currentTimeMillis()
        val m = WAKE.find(text)
        var cmd: String? = null
        if (m != null) {
            cmd = text.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?', '।')
            if (cmd.isEmpty()) {
                followUpUntil = now + FOLLOW_UP_MS
                JarvisLog.add("🎙 \"$text\"")
                speak("जी, बोलिए")
                return
            }
        } else if (now < followUpUntil) {
            cmd = text
        }
        if (cmd == null) {
            startListening(); return
        }
        process(cmd)
    }

    // ---------- thinking ----------

    private fun process(cmd: String) {
        busy = true
        recognizer?.cancel()
        JarvisLog.add("🗣 $cmd")
        exec.execute {
            val reply = try {
                brain.ask(cmd)
            } catch (e: Exception) {
                JarvisLog.add("❌ ${e.message}")
                if (e.message?.contains("429") == true) {
                    "अभी free limit पूरी हो गई है, थोड़ी देर बाद बोलिए।"
                } else {
                    "माफ़ कीजिए, कुछ गड़बड़ हो गई। इंटरनेट या API key चेक करिए।"
                }
            }
            JarvisLog.add("🤖 $reply")
            main.post { speak(reply) }
        }
    }

    // ---------- speaking ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            JarvisLog.add("❌ Text-to-speech शुरू नहीं हुआ")
            return
        }
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { onSpeechDone() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onSpeechDone() }
            }
        })
    }

    private fun speak(raw: String) {
        busy = true
        recognizer?.cancel()
        val text = raw.replace(Regex("[*#`_>]"), "").take(3500)
        val t = tts
        if (!ttsReady || t == null || text.isBlank()) {
            onSpeechDone(); return
        }
        val hasHindi = text.any { it in 'ऀ'..'ॿ' }
        t.setLanguage(if (hasHindi) Locale("hi", "IN") else Locale("en", "IN"))
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun onSpeechDone() {
        busy = false
        followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS
        startListening()
    }

    override fun onDestroy() {
        running = false
        JarvisLog.add("⏹ Jarvis बंद")
        main.removeCallbacksAndMessages(null)
        resetRecognizer()
        tts?.stop()
        tts?.shutdown()
        exec.shutdownNow()
        super.onDestroy()
    }
}
