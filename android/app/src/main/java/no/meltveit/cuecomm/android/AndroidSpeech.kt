package no.meltveit.cuecomm.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import no.meltveit.cuecomm.core.Channel
import no.meltveit.cuecomm.core.SpeechController
import java.util.UUID

/**
 * Speech to text for one channel at a time with Android's SpeechRecognizer. It turns the recogniser's
 * sessions into chat messages the way the Mac does: partial text while someone speaks, one committed
 * message when they pause for `silence` seconds, then a fresh session for the next sentence.
 */
class AndroidSpeech(context: Context) : SpeechController {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override var onPartial: (UUID, String, UUID) -> Unit = { _, _, _ -> }
    override var onCommit: (UUID, String, UUID) -> Unit = { _, _, _ -> }
    override var onStatus: (UUID, String) -> Unit = { _, _ -> }
    override var onLevel: (UUID, Float) -> Unit = { _, _ -> }

    private var recognizer: SpeechRecognizer? = null
    private var channel: Channel? = null
    private var silenceMs = 1200L
    private var running = false
    private var generation = 0
    private var lastPartial = ""
    private var lastChange = 0L
    private var lastLevelAt = 0L
    private var utterance = UUID.randomUUID()
    private var failures = 0
    private var forceOnline = false         // the offline language pack is missing, so use the network recogniser

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            main.postDelayed(this, 250)
        }
    }

    override fun start(channel: Channel, silenceSeconds: Double) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onStatus(channel.id, "No speech recognition service on this device")
            return
        }
        this.channel = channel
        silenceMs = (maxOf(0.4, silenceSeconds) * 1000).toLong()
        running = true
        failures = 0
        forceOnline = false
        lastPartial = ""
        utterance = UUID.randomUUID()
        begin()
        main.postDelayed(ticker, 250)
    }

    override fun stop() {
        val ch = channel
        val text = lastPartial
        val utt = utterance
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        lastPartial = ""
        channel = null
        if (ch != null && text.isNotEmpty()) onCommit(ch.id, text, utt)
    }

    // MARK: internals

    private fun begin() {
        val ch = channel ?: return
        if (!running) return
        val g = ++generation
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        val offline = ch.onDeviceOnly && !forceOnline
        val r = if (Build.VERSION.SDK_INT >= 33 && offline && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        recognizer = r
        r.setRecognitionListener(Listener(g, ch))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, ch.localeID)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure { restart(g, 1000) }
    }

    private fun restart(g: Int, delayMs: Long) {
        if (!running || g != generation) return
        main.postDelayed({ if (running && g == generation) begin() }, delayMs)
    }

    /** Finish the current utterance (emit it as a message) and open a fresh recognition session. */
    private fun commitAndRestart(g: Int, finalText: String?) {
        if (!running || g != generation) return
        val ch = channel ?: return
        val text = (finalText ?: "").ifBlank { lastPartial }.trim()
        val utt = utterance
        lastPartial = ""
        utterance = UUID.randomUUID()
        generation++                       // callbacks from the finished session are now stale
        recognizer?.let { runCatching { it.cancel() } }
        if (text.isNotEmpty()) onCommit(ch.id, text, utt)
        main.postDelayed({ if (running) begin() }, 80)
    }

    private fun tick() {
        val due = lastPartial.isNotEmpty() && System.currentTimeMillis() - lastChange > silenceMs
        if (due) commitAndRestart(generation, null)
    }

    private fun partial(g: Int, text: String) {
        if (!running || g != generation) return
        val ch = channel ?: return
        val t = text.trim()
        if (t.isEmpty() || t == lastPartial) return
        lastPartial = t
        lastChange = System.currentTimeMillis()
        failures = 0
        onPartial(ch.id, t, utterance)
    }

    private inner class Listener(private val g: Int, private val ch: Channel) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (g == generation) onStatus(ch.id, "Listening…") }
        override fun onBeginningOfSpeech() { if (g == generation) onStatus(ch.id, "Hearing speech") }
        override fun onRmsChanged(rmsdB: Float) {
            if (g != generation) return
            val now = System.currentTimeMillis()
            if (now - lastLevelAt < 90) return
            lastLevelAt = now
            onLevel(ch.id, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {
            partial(g, b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return)
        }
        override fun onResults(b: Bundle?) {
            commitAndRestart(g, b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            if (!running || g != generation) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> commitAndRestart(g, null)   // silence: normal
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(g, 600)
                // LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE: no offline pack for this language. Prefer offline, but
                // keep the channel working over the network rather than failing every second.
                12, 13 -> if (ch.onDeviceOnly && !forceOnline) {
                    forceOnline = true
                    onStatus(ch.id, "Offline language pack missing, using online recognition")
                    restart(g, 200)
                } else {
                    failures++
                    onStatus(ch.id, "This language is not available for speech recognition")
                    restart(g, if (failures < 5) 1500 else 5000)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onStatus(ch.id, "Microphone permission is off")
                }
                else -> {
                    failures++
                    onStatus(ch.id, describe(error))
                    restart(g, if (failures < 5) 700 else 3000)
                }
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No network for speech recognition. Download the offline language pack in Android settings."
        SpeechRecognizer.ERROR_AUDIO -> "Audio input error"
        SpeechRecognizer.ERROR_SERVER -> "Speech service error"
        else -> "Speech recognition error ($code)"
    }
}
