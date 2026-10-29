package no.meltveit.cuecomm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Talks to a real CueComm for Mac. Skipped unless a Mac is named:
 *   ./gradlew testDebugUnitTest -Pmac=127.0.0.1:47821 -PmacDir=/path/to/mac/support/dir -PmacOsc=9021
 * The Mac must be hosting a session and have an OSC-triggered flash automation on /test/cue.
 */
class MacCrossCheck {
    private val mac = System.getProperty("cuecomm.mac")
    private val macDir = System.getProperty("cuecomm.macdir")?.let { File(it) }
    private val macOsc = System.getProperty("cuecomm.macosc")?.toIntOrNull()

    @Test fun joinsTheMacAndExchangesEverything() {
        assumeTrue("no Mac given", mac != null)
        val phone = Node("Pixel")
        try {
            phone.on { joinByAddress(mac!!) }
            eventually("join the Mac's session") { phone.state.settings.sessionName.isNotEmpty() }
            println("MAC: joined session '${phone.state.settings.sessionName}'")
            eventually("link with the Mac") { phone.state.peers.isNotEmpty() }
            println("MAC: peers = ${phone.state.peers}")

            // 1. The Mac's channel and its recent history arrive.
            eventually("Mac channel") { phone.channels.any { it.isRemote } }
            val macChannel = phone.channels.first { it.isRemote }
            println("MAC: remote channel '${macChannel.name}' owner '${macChannel.ownerName}'")
            eventually("Mac history") { phone.state.messages[macChannel.id]?.any { it.text == "history line from the Mac" } == true }

            // 2. What we type reaches the Mac (it saves its history two seconds after a change).
            val mine = phone.channels.first { !it.isRemote }
            phone.on { send("hello from Android", mine.id) }
            if (macDir != null) {
                eventually("Mac stored our message", timeoutMs = 15_000) {
                    File(macDir, "history.json").readText().contains("hello from Android")
                }
                println("MAC: stored 'hello from Android' in its history")
            }

            // 3. And messages typed into the Mac's own channel from here too.
            phone.on { send("reply into your channel", macChannel.id) }
            if (macDir != null) {
                eventually("Mac stored the reply", timeoutMs = 15_000) { File(macDir, "history.json").readText().contains("reply into your channel") }
            }

            // 4. A cue fired on the Mac (by OSC) flashes this device.
            if (macOsc != null) {
                val before = phone.state.flash.token
                val data = OscCodec.encode(OscMessage("/test/cue"))
                DatagramSocket().use { it.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), macOsc)) }
                eventually("flash from the Mac's cue") { phone.state.flash.token > before }
                println("MAC: flash received, colour ${phone.state.flash.colorHex}, banner '${phone.state.lastCue?.title}' / '${phone.state.lastCue?.detail}'")
                assertEquals("FF4FA3", phone.state.flash.colorHex)
            }

            // 5. A cue fired here goes to the Mac as a frame it can decode; the link must survive it.
            val cue = phone.on { addCue("zzz") }
            phone.on { fire(state.value.automations.first { it.id == cue }, ActionContext("zzz", "Pixel", "zzz"), force = true) }
            Thread.sleep(1500)
            assertTrue(phone.state.peers.isNotEmpty())
        } finally {
            phone.stop()
        }
    }

    /** The other direction: the Mac dials us. Start a Mac with manualPeers = [127.0.0.1:<port>] within 60 s. */
    @Test fun theMacCanDialUs() {
        val port = System.getProperty("cuecomm.listen")?.toIntOrNull()
        assumeTrue("no listen port given", port != null)
        val phone = Node("Pixel", port!!, "Friday")
        try {
            println("ANDROID: listening on $port, waiting for the Mac to dial…")
            eventually("the Mac dials in", timeoutMs = 90_000) { phone.state.peers.isNotEmpty() }
            println("ANDROID: peers = ${phone.state.peers}")
            eventually("Mac channel arrives") { phone.channels.any { it.isRemote } }
            println("ANDROID: got Mac channel '${phone.channels.first { it.isRemote }.name}'")
        } finally {
            phone.stop()
        }
    }
}
