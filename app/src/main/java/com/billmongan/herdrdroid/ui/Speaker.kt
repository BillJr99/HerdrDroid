package com.billmongan.herdrdroid.ui

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale
import com.billmongan.herdrdroid.voice.VoiceAudio
import com.billmongan.herdrdroid.voice.voiceRoute

/**
 * Reads replies aloud with the phone's own text-to-speech. One engine for the app, started on
 * first use. Markdown symbols, code blocks and links are left out of what is spoken.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var starting = false
    private var pending: String? = null
    private var onRead: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var appContext: Context? = null
    private var route: VoiceAudio.Lease? = null
    /** The reply being read, so its button can say "Stop reading"; cleared when speech ends. */
    var speakingKey by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun speak(context: Context, key: String, text: String, onRead: (() -> Unit)? = null) {
        if (speakingKey == key) { stop(); return }
        val clean = speakable(text)
        appContext = context.applicationContext
        audio = appContext?.getSystemService(AudioManager::class.java)
        this.onRead = onRead
        speakingKey = key
        val t = tts
        if (t != null && ready) { play(t, clean, key); return }
        pending = clean
        if (t == null && !starting) {
            starting = true
            tts = TextToSpeech(context.applicationContext) { status ->
                starting = false
                ready = status == TextToSpeech.SUCCESS
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ended(id, completed = true) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { ended(id) }
                    override fun onError(id: String?, errorCode: Int) { ended(id) }
                })
                if (ready) pending?.let { text -> speakingKey?.let { key -> tts?.let { play(it, text, key) } } }
                else {
                    speakingKey = null
                    this.onRead = null
                    tts?.shutdown()
                    tts = null
                }
                pending = null
            }
        }
    }

    private fun play(engine: TextToSpeech, text: String, key: String) {
        if (route != null) engine.stop()
        releaseFocus()
        val context = appContext ?: return
        val lease = try {
            VoiceAudio.acquire(context, voiceRoute(context))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, e.message ?: "Voice audio is unavailable.", android.widget.Toast.LENGTH_LONG).show()
            stop()
            return
        }
        route = lease
        val until = android.os.SystemClock.elapsedRealtime() + 5_000L
        awaitRoute(engine, text, key, lease, until)
    }

    private fun awaitRoute(engine: TextToSpeech, text: String, key: String, lease: VoiceAudio.Lease, until: Long) {
        if (route !== lease || speakingKey != key) return
        if (!lease.routed()) {
            if (android.os.SystemClock.elapsedRealtime() < until) {
                main.postDelayed({ awaitRoute(engine, text, key, lease, until) }, 100)
            } else {
                appContext?.let { android.widget.Toast.makeText(it, "The selected voice audio route did not connect.", android.widget.Toast.LENGTH_LONG).show() }
                stop()
            }
            return
        }
        val attributes = lease.attributes
        engine.setAudioAttributes(attributes)
        val manager = audio
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && speakingKey == key) stop()
            }, main)
            .build()
        if (manager != null && manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = request
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, key) == TextToSpeech.SUCCESS) {
                if (lease.communication) monitorRoute(lease)
                return
            }
        }
        stop()
    }

    private fun monitorRoute(lease: VoiceAudio.Lease) {
        main.postDelayed({
            if (route === lease) {
                if (lease.routed()) monitorRoute(lease) else stop()
            }
        }, 200)
    }

    private fun ended(id: String?, completed: Boolean = false) {
        main.post {
            if (id != null && speakingKey == id) {
                val read = onRead
                onRead = null
                speakingKey = null
                releaseFocus()
                if (completed) read?.invoke()
            }
        }
    }

    private fun releaseFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
        route?.close()
        route = null
    }

    fun stop() {
        pending = null
        onRead = null
        tts?.stop()
        speakingKey = null
        releaseFocus()
    }

    /** Text as it should sound: no code blocks, Markdown marks, link targets or table rules. */
    fun speakable(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), " (code) ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s*[|:\\- ]{3,}$"), "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("[*_~>|#]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
