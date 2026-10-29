package no.meltveit.cuecomm.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTests {
    private val nodes = mutableListOf<Node>()
    private fun node(name: String, session: String = "", open: Boolean = false, port: Int = freePort()) =
        Node(name, port, session, open = open).also { nodes += it }

    @After fun tearDown() { nodes.forEach { it.stop() } }

    private fun addr(n: Node) = "127.0.0.1:${n.port}"

    @Test fun joinByAddressNeedsNoSessionName() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }

        eventually("phone in the host's session") { phone.state.settings.sessionName == "Friday" }
        assertEquals(AddressJoin.Idle, phone.state.addressJoin)
        eventually("both see each other") { phone.state.peers == listOf("Host") && host.state.peers == listOf("Phone") }
        eventually("manual address shows connected") { phone.state.manualStatus.values.any { it is ManualLinkState.Connected } }
        assertEquals(listOf(addr(host)), phone.state.settings.manualPeers)
    }

    @Test fun channelsAndMessagesFlowBothWays() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }

        // The host's channels appear on the phone as remote channels, and the phone's on the host.
        val hostChannel = host.channels.first { !it.isRemote }
        eventually("host channel on the phone") { phone.channels.any { it.id == hostChannel.id && it.isRemote && it.ownerName == "Host" } }
        val phoneChannel = phone.channels.first { !it.isRemote }
        eventually("phone channel on the host") { host.channels.any { it.id == phoneChannel.id && it.isRemote } }

        host.on { send("standby lights", hostChannel.id) }
        eventually("host message on the phone") { phone.state.messages[hostChannel.id]?.any { it.text == "standby lights" && it.remote } == true }

        phone.on { send("copy that", phoneChannel.id) }
        eventually("phone message on the host") { host.state.messages[phoneChannel.id]?.any { it.text == "copy that" && it.remote } == true }

        // No echo loop: each message exists once on each side.
        Thread.sleep(500)
        assertEquals(1, phone.state.messages[hostChannel.id]!!.count { it.text == "standby lights" })
        assertEquals(1, host.state.messages[hostChannel.id]!!.count { it.text == "standby lights" })
    }

    @Test fun lateJoinersGetTheRecentTranscript() {
        val host = node("Host", session = "Friday")
        val ch = host.channels.first { !it.isRemote }
        host.on { send("before you arrived", ch.id) }
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }
        eventually("history") { phone.state.messages[ch.id]?.any { it.text == "before you arrived" } == true }
        assertEquals(0, phone.state.unread[ch.id] ?: 0)
    }

    @Test fun aCueFlashesEveryDevice() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }

        // The phone hears "go" on its own channel; the host must flash too.
        val ch = phone.channels.first { !it.isRemote }
        val before = host.state.flash.token
        phone.on { post(ch.id, "okay go now", utterance = java.util.UUID.randomUUID()) }
        eventually("phone flashed") { phone.state.flash.token > 0 }
        eventually("host flashed from the network") { host.state.flash.token > before }
        assertEquals("Phone", host.state.lastCue?.detail?.removePrefix("From ")?.substringBefore("  ·"))
    }

    @Test fun flashEveryoneOffStaysLocal() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }
        phone.on {
            state.value.automations.filter { it.isCue }.forEach { a -> upsert(a.copy(actions = a.actions.map { it.copy(flashEveryone = false) })) }
        }
        val ch = phone.channels.first { !it.isRemote }
        phone.on { post(ch.id, "go", utterance = java.util.UUID.randomUUID()) }
        eventually("phone flashed") { phone.state.flash.token > 0 }
        Thread.sleep(800)
        assertEquals(0, host.state.flash.token)
    }

    @Test fun receivingFlashesCanBeSwitchedOff() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        host.on { updateSettings { it.copy(flashOnRemoteCues = false) } }
        phone.on { joinByAddress(addr(host)) }
        eventually("connected") { host.state.peers.isNotEmpty() && phone.state.peers.isNotEmpty() }
        val ch = phone.channels.first { !it.isRemote }
        phone.on { post(ch.id, "go", utterance = java.util.UUID.randomUUID()) }
        Thread.sleep(800)
        assertEquals(0, host.state.flash.token)
    }

    @Test fun refusedPortGivesAClearReason() {
        val phone = node("Phone")
        phone.on { joinByAddress("127.0.0.1:${freePort()}") }
        eventually("failure") { phone.state.addressJoin is AddressJoin.Failed }
        val why = (phone.state.addressJoin as AddressJoin.Failed).why
        assertTrue(why, why.startsWith("Refused"))
        assertFalse(phone.state.inSession)
    }

    @Test fun aDeviceWithoutASessionCannotBeJoined() {
        val idle = node("Idle", open = true)               // open sharing: listening, but no session
        val phone = node("Phone")
        phone.on { joinByAddress(addr(idle)) }
        eventually("failure") { phone.state.addressJoin is AddressJoin.Failed }
        assertTrue((phone.state.addressJoin as AddressJoin.Failed).why.contains("not hosting a session"))
        assertFalse(phone.state.inSession)
    }

    @Test fun cannotJoinAnotherSessionWhileInOne() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone", session = "Other")
        phone.on { joinByAddress(addr(host)) }
        eventually("failure") { phone.state.addressJoin is AddressJoin.Failed }
        val why = (phone.state.addressJoin as AddressJoin.Failed).why
        assertTrue(why, why.contains("“Friday”") && why.contains("Leave “Other”"))
        assertEquals("Other", phone.state.settings.sessionName)
    }

    @Test fun joiningYourOwnAddressIsRefusedPolitely() {
        val me = node("Me", session = "Friday")
        me.on { joinByAddress(addr(me)) }
        eventually("failure") { me.state.addressJoin is AddressJoin.Failed }
        assertEquals("That address is this device.", (me.state.addressJoin as AddressJoin.Failed).why)
    }

    @Test fun badTextIsRejectedBeforeAnyNetwork() {
        val phone = node("Phone")
        phone.on { joinByAddress("not an address!") }
        assertTrue(phone.state.addressJoin is AddressJoin.Failed)
    }

    @Test fun bothSidesDialingKeepsOneLink() {
        val a = node("Alpha", session = "Friday")
        val b = node("Bravo", session = "Friday")
        a.on { updateSettings { it.copy(manualPeers = listOf(addr(b))) } }
        b.on { updateSettings { it.copy(manualPeers = listOf(addr(a))) } }
        eventually("linked") { a.state.peers == listOf("Bravo") && b.state.peers == listOf("Alpha") }
        Thread.sleep(3000)                                          // the tie-break must settle and stay settled
        assertEquals(listOf("Bravo"), a.state.peers)
        assertEquals(listOf("Alpha"), b.state.peers)
        // and the link still carries traffic
        val ch = a.channels.first { !it.isRemote }
        a.on { send("still here", ch.id) }
        eventually("traffic after the tie-break") { b.state.messages[ch.id]?.any { it.text == "still here" } == true }
    }

    @Test fun reconnectsAfterTheHostRestarts() {
        val port = freePort()
        val hostDir = java.nio.file.Files.createTempDirectory("cuecomm-host").toFile()
        var host = Node("Host", port, "Friday", hostDir).also { nodes += it }
        val phone = node("Phone")
        phone.on { joinByAddress("127.0.0.1:$port") }
        eventually("connected") { phone.state.peers == listOf("Host") }

        host.stop()
        eventually("phone notices") { phone.state.peers.isEmpty() }
        assertTrue(phone.state.manualStatus.values.any { it is ManualLinkState.Failed })

        host = Node("Host", port, "Friday", hostDir).also { nodes += it }
        eventually("phone reconnects by itself", timeoutMs = 20_000) { phone.state.peers == listOf("Host") }
    }

    @Test fun leavingForgetsTheSessionAndAddresses() {
        val host = node("Host", session = "Friday")
        val phone = node("Phone")
        phone.on { joinByAddress(addr(host)) }
        eventually("connected") { phone.state.peers.isNotEmpty() }
        phone.on { leaveSession() }
        assertFalse(phone.state.inSession)
        assertTrue(phone.state.settings.manualPeers.isEmpty())
        assertTrue(phone.channels.none { it.isRemote })
        eventually("host sees the phone go") { host.state.peers.isEmpty() }
    }

    @Test fun aWrongSessionIsNeverLinked() {
        val a = node("Alpha", session = "Friday")
        val b = node("Bravo", session = "Saturday")
        b.on { updateSettings { it.copy(manualPeers = listOf(addr(a))) } }
        eventually("bravo reports why") { b.state.manualStatus.values.any { it is ManualLinkState.Failed } }
        assertTrue(b.state.peers.isEmpty() && a.state.peers.isEmpty())
    }

    @Test fun settingsSurviveARestart() {
        val dir = java.nio.file.Files.createTempDirectory("cuecomm-persist").toFile()
        val first = Node("Phone", freePort(), "Friday", dir).also { nodes += it }
        val id = first.on { instanceID }
        first.on { addCue("lights"); save() }
        Thread.sleep(500)
        first.stop()
        val second = Node("Phone", freePort(), "", dir).also { nodes += it }
        assertEquals(id, second.on { instanceID })
        assertTrue(second.state.automations.any { it.trigger.phrase == "lights" })
    }

    @Test fun transcriptSurvivesARestart() {
        val dir = java.nio.file.Files.createTempDirectory("cuecomm-history").toFile()
        val first = Node("Phone", freePort(), "", dir).also { nodes += it }
        val ch = first.channels.first { !it.isRemote }
        first.on { send("remember me", ch.id) }
        Thread.sleep(2800)                                          // the store saves two seconds after a change
        first.stop()
        val second = Node("Phone", freePort(), "", dir).also { nodes += it }
        assertEquals(listOf("remember me"), second.state.messages[ch.id]?.map { it.text })
    }
}
