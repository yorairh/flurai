package com.flurai.app.core.speech

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Japanese aloud with the phone's text-to-speech engine. Started on first use and kept for
 * the app's lifetime. Pass kana readings rather than kanji where possible: kanji can be misread.
 */
@Singleton
class JapaneseSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Status { NotStarted, Starting, Ready, MissingVoice, Unavailable }

    private val _status = MutableStateFlow(Status.NotStarted)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var tts: TextToSpeech? = null

    fun start() {
        if (_status.value != Status.NotStarted) return
        _status.value = Status.Starting
        tts = TextToSpeech(context) { result ->
            _status.value = if (result != TextToSpeech.SUCCESS) {
                Status.Unavailable
            } else {
                when (tts?.setLanguage(Locale.JAPAN)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED, null -> Status.MissingVoice
                    else -> Status.Ready
                }
            }
        }
    }

    fun speak(text: String) {
        start()
        if (_status.value == Status.Ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        }
    }

    /** Opens the system screen for downloading voice data (when Japanese is missing). */
    fun installVoiceIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
