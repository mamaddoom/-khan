package com.mozkhan.shop.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Spoken answers through the phone's own text-to-speech engine (offline when the Persian voice is installed).
 * Off when the user turned «پاسخ صوتی» off, or when the phone has no Persian voice (then the screen still shows everything).
 */
object VoiceSpeaker {
    enum class Status { UNKNOWN, READY, NO_PERSIAN, FAILED }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private val fa = Locale("fa", "IR")

    fun init(ctx: Context) {
        if (tts != null) return
        // the engine is captured here: its ready-callback may come before the constructor's result is stored in [tts]
        var made: TextToSpeech? = null
        tts = runCatching {
            TextToSpeech(ctx.applicationContext) { st ->
                val t = made ?: tts
                if (st != TextToSpeech.SUCCESS || t == null) { _status.value = Status.FAILED; return@TextToSpeech }
                val r = runCatching { t.setLanguage(fa) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                _status.value = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Status.NO_PERSIAN else Status.READY
                if (_status.value == Status.READY) pending?.let { say(it) }
                pending = null
            }.also { made = it }
        }.getOrNull()
        if (tts == null) _status.value = Status.FAILED
    }

    /** Speaks [text] (replacing whatever is being said). Does nothing when [enabled] is false. */
    fun speak(ctx: Context, text: String, enabled: Boolean) {
        if (!enabled || text.isBlank()) return
        init(ctx)
        when (_status.value) {
            Status.READY -> say(text)
            Status.UNKNOWN -> pending = text
            else -> Unit
        }
    }

    private fun say(text: String) {
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mozkhan") }
    }

    /** Silence: before the microphone opens (the recognizer must not hear the phone talking). */
    fun stop() { runCatching { tts?.stop() } }

    /** Opens the system screen that installs voice data (to add the Persian voice). */
    fun installIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
