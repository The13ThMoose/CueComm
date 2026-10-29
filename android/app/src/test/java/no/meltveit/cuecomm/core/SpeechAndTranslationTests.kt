package no.meltveit.cuecomm.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Stands in for Android's SpeechRecognizer: the test says what was "heard". */
class FakeSpeech : SpeechController {
    override var onPartial: (UUID, String, UUID) -> Unit = { _, _, _ -> }
    override var onCommit: (UUID, String, UUID) -> Unit = { _, _, _ -> }
    override var onStatus: (UUID, String) -> Unit = { _, _ -> }
    override var onLevel: (UUID, Float) -> Unit = { _, _ -> }
    @Volatile var started: Channel? = null
    @Volatile var starts = 0
    override fun start(channel: Channel, silenceSeconds: Double) { started = channel; starts++ }
    override fun stop() { started = null }
}

class SpeechAndTranslationTests {
    private val nodes = mutableListOf<Node>()
    @After fun tearDown() { nodes.forEach { it.stop() } }

    private fun node(name: String, session: String = "", platform: Platform = object : Platform {}) =
        Node(name, freePort(), session, platform = platform).also { nodes += it }

    @Test fun listeningSpeaksIntoTheViewedChannel() {
        val speech = FakeSpeech()
        val phone = node("Phone", platform = object : Platform { override val speech = speech })
        val foh = phone.channels.first { it.name == "FOH" }
        phone.on { select(Selection.Chan(foh.id)); toggleListening() }
        eventually("speech started on FOH") { speech.started?.id == foh.id }
        assertTrue(phone.state.isListening)
        phone.on { toggleListening() }
        eventually("speech stopped") { speech.started == null }
        assertFalse(phone.state.isListening)
    }

    @Test fun spokenWordsBecomeMessagesAndCuesFireBeforeTheSentenceEnds() {
        val speech = FakeSpeech()
        val host = node("Host", session = "Friday")
        val phone = node("Phone", platform = object : Platform { override val speech = speech })
        phone.on { joinByAddress("127.0.0.1:${host.port}") }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }

        phone.on { toggleListening() }
        eventually("listening") { speech.started != null }
        val ch = speech.started!!
        val utterance = UUID.randomUUID()

        // Partial text shows live, and the cue word fires at once, on every device.
        speech.onPartial(ch.id, "okay go", utterance)
        eventually("live text") { phone.state.partials[ch.id] == "okay go" }
        eventually("phone flashed on the partial") { phone.state.flash.token == 1 }
        eventually("host flashed too") { host.state.flash.token == 1 }
        assertTrue(phone.state.messages[ch.id].isNullOrEmpty())

        // The finished sentence becomes one message, shared with the session, and does not fire the cue again.
        speech.onCommit(ch.id, "okay go now", utterance)
        eventually("message on the phone") { phone.state.messages[ch.id]?.any { it.text == "okay go now" } == true }
        eventually("message on the host") { host.state.messages[ch.id]?.any { it.text == "okay go now" && it.remote } == true }
        assertNotNull(phone.state.messages[ch.id]!!.first().speaker)
        assertTrue(phone.state.partials[ch.id] == null)
        Thread.sleep(500)
        assertEquals(1, phone.state.flash.token)
    }

    @Test fun cueWordsAreKeywordsOnlyOnTheChannelsTheyListenTo() {
        val phone = node("Phone")
        val monitors = phone.channels.first { it.name == "Monitors" }
        val foh = phone.channels.first { it.name == "FOH" }
        phone.on {
            val cue = state.value.automations.first { it.trigger.phrase == "go" }
            upsert(cue.copy(trigger = cue.trigger.copy(channelID = monitors.id)))
        }
        phone.on { post(foh.id, "go", utterance = UUID.randomUUID()) }
        Thread.sleep(300)
        assertEquals(0, phone.state.flash.token)
        phone.on { post(monitors.id, "go", utterance = UUID.randomUUID()) }
        eventually("flash on the right channel") { phone.state.flash.token == 1 }
    }

    @Test fun incomingSpeechFromAnotherDeviceDoesNotRepeatOscUnlessAsked() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress("127.0.0.1:${host.port}") }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }
        val ch = host.channels.first { !it.isRemote }
        eventually("host channel on the phone") { phone.channels.any { it.id == ch.id } }
        host.on { post(ch.id, "go", utterance = UUID.randomUUID()) }
        eventually("host flashed") { host.state.flash.token == 1 }
        eventually("the phone flashed from the network frame") { phone.state.flash.token == 1 }
        // Exactly one flash on the phone: its own automation did not also fire for the remote words.
        Thread.sleep(800)
        assertEquals(1, phone.state.flash.token)
        assertTrue("the phone's own cue must not have fired", phone.state.lastFired.isEmpty())
    }

    @Test fun translationIsAttachedToIncomingMessages() {
        val translator = Translator { text, source, target, done -> done("[$source>$target] $text") }
        val host = node("Host", session = "Friday")
        val phone = node("Phone", platform = object : Platform { override val translator = translator })
        phone.on { updateSettings { it.copy(preferredLanguage = "nb") } }
        phone.on { joinByAddress("127.0.0.1:${host.port}") }
        eventually("connected") { phone.state.peers.isNotEmpty() }
        val ch = host.channels.first { !it.isRemote }
        eventually("remote channel on the phone") { phone.channels.any { it.id == ch.id && it.isRemote } }
        phone.on { upsert(state.value.channel(ch.id)!!.copy(translate = true)) }

        host.on { send("hello everyone", ch.id) }
        eventually("translation attached") { phone.state.messages[ch.id]?.any { it.translation == "[en>nb] hello everyone" } == true }
    }

    @Test fun nothingIsTranslatedIntoTheSameLanguage() {
        var calls = 0
        val translator = Translator { _, _, _, done -> calls++; done("x") }
        val host = node("Host", session = "Friday")
        val phone = node("Phone", platform = object : Platform { override val translator = translator })
        phone.on { joinByAddress("127.0.0.1:${host.port}") }
        eventually("connected") { phone.state.peers.isNotEmpty() }
        val ch = host.channels.first { !it.isRemote }
        eventually("remote channel") { phone.channels.any { it.id == ch.id } }
        phone.on { upsert(state.value.channel(ch.id)!!.copy(translate = true)) }
        host.on { send("same language", ch.id) }
        eventually("message arrived") { phone.state.messages[ch.id]?.any { it.text == "same language" } == true }
        Thread.sleep(300)
        assertEquals(0, calls)
    }
}
