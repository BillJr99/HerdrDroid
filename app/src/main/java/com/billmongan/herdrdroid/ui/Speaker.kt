package com.billmongan.herdrdroid.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads replies aloud with the phone's own text-to-speech. One engine for the app, started on
 * first use. Markdown symbols, code blocks and links are left out of what is spoken.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var starting = false
    private var pending: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    /** The reply being read, so its button can say "Stop reading"; cleared when speech ends. */
    var speakingKey by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun speak(context: Context, key: String, text: String) {
        if (speakingKey == key) { stop(); return }
        val clean = speakable(text)
        audio = context.applicationContext.getSystemService(AudioManager::class.java)
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
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ended(id) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { ended(id) }
                    override fun onError(id: String?, errorCode: Int) { ended(id) }
                })
                if (ready) pending?.let { text -> speakingKey?.let { key -> tts?.let { play(it, text, key) } } }
                else {
                    speakingKey = null
                    tts?.shutdown()
                    tts = null
                }
                pending = null
            }
        }
    }

    private fun play(engine: TextToSpeech, text: String, key: String) {
        releaseFocus()
        val manager = audio
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && speakingKey == key) stop()
            }, main)
            .build()
        if (manager != null && manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = request
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, key) == TextToSpeech.SUCCESS) return
        }
        stop()
    }

    private fun ended(id: String?) {
        main.post {
            if (speakingKey == id) {
                speakingKey = null
                releaseFocus()
            }
        }
    }

    private fun releaseFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun stop() {
        pending = null
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
