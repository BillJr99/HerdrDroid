package com.billmongan.herdrdroid.ui

import android.content.Context
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
    private var pending: String? = null
    /** The reply being read, so its button can say "Stop reading"; cleared when speech ends. */
    var speakingKey by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun speak(context: Context, key: String, text: String) {
        if (speakingKey == key) { stop(); return }
        val clean = speakable(text)
        speakingKey = key
        val t = tts
        if (t != null && ready) { t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, key); return }
        pending = clean
        if (t == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (speakingKey == id) speakingKey = null }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (speakingKey == id) speakingKey = null }
                })
                if (ready) pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, speakingKey) }
                pending = null
            }
        }
    }

    fun stop() {
        tts?.stop()
        speakingKey = null
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
