package com.example.dovykhoda

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class VoiceSpeaker(
    context: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
) : TextToSpeech.OnInitListener {
    private val initResult = CompletableDeferred<Boolean>()
    private var engine: TextToSpeech? = null
    private var speaking = false

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            initResult.complete(false)
            return
        }
        val ready = runCatching {
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            val language = tts.setLanguage(Locale("ru", "RU"))
            tts.setSpeechRate(0.95f)
            tts.setPitch(1f)
            language >= TextToSpeech.LANG_AVAILABLE
        }.getOrDefault(false)
        if (!ready) Log.w(TAG, "Russian TTS voice is unavailable")
        initResult.complete(ready)
    }

    suspend fun speak(text: String): Boolean {
        if (withTimeoutOrNull(5_000L) { initResult.await() } != true) return false
        val tts = engine ?: return false
        val utteranceId = "alarm-${UUID.randomUUID()}"
        val finished = AtomicBoolean(false)
        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                fun finish(result: Boolean) {
                    if (finished.compareAndSet(false, true)) {
                        notifySpeechEnd()
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id == utteranceId && !finished.get()) notifySpeechStart()
                    }

                    override fun onDone(id: String?) {
                        if (id == utteranceId) finish(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) finish(false)
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) finish(false)
                    }

                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (id == utteranceId) finish(false)
                    }
                })
                continuation.invokeOnCancellation {
                    finished.set(true)
                    runCatching { tts.stop() }
                    notifySpeechEnd()
                }
                val result = runCatching {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }.getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) finish(false)
            }
        } ?: false
    }

    private fun notifySpeechStart() {
        synchronized(this) {
            if (!speaking) {
                speaking = true
                onSpeechStart()
            }
        }
    }

    private fun notifySpeechEnd() {
        synchronized(this) {
            if (speaking) {
                speaking = false
                onSpeechEnd()
            }
        }
    }

    fun shutdown() {
        initResult.complete(false)
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        notifySpeechEnd()
    }

    private companion object {
        const val TAG = "VoiceSpeaker"
    }
}
