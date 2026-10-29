package no.meltveit.cuecomm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class OscTests {
    @Test fun encodesLikeTheMac() {
        // "/cue/go" + ",i" + 1, padded to four bytes: the same bytes the Mac's OSCCodec writes.
        val b = OscCodec.encode(OscMessage("/cue/go", listOf(OscArg.I(1))))
        val expected = "/cue/go\u0000,i\u0000\u0000".toByteArray() + byteArrayOf(0, 0, 0, 1)
        assertEquals(expected.toList(), b.toList())
    }

    @Test fun roundTripsAllArgumentTypes() {
        val m = OscMessage("/a/b", listOf(OscArg.I(-7), OscArg.F(0.5f), OscArg.S("hello world"), OscArg.S("")))
        assertEquals(m, OscCodec.decode(OscCodec.encode(m)).single())
    }

    @Test fun decodesBundles() {
        val one = OscCodec.encode(OscMessage("/x", listOf(OscArg.I(1))))
        val two = OscCodec.encode(OscMessage("/y"))
        fun len(n: Int) = byteArrayOf(0, 0, 0, n.toByte())
        val bundle = "#bundle\u0000".toByteArray() + ByteArray(8) + len(one.size) + one + len(two.size) + two
        assertEquals(listOf("/x", "/y"), OscCodec.decode(bundle).map { it.address })
    }

    @Test fun ignoresGarbage() {
        assertTrue(OscCodec.decode(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(OscCodec.decode(ByteArray(0)).isEmpty())
    }

    @Test fun parsesArguments() {
        assertEquals(listOf(OscArg.I(1), OscArg.F(0.5f), OscArg.S("hello world")), OscCodec.parseArgs("1 0.5 \"hello world\""))
        assertEquals(listOf(OscArg.S("12")), OscCodec.parseArgs("\"12\""))
    }

    @Test fun matchesWildcards() {
        assertTrue(OscCodec.matches("/cue/go", "/cue/*"))
        assertTrue(OscCodec.matches("/cue/go", "/cue/go"))
        assertFalse(OscCodec.matches("/cue/go", "/cue/stop"))
        assertFalse(OscCodec.matches("/cue/go", ""))
    }
}

class TextTests {
    @Test fun wholeWordOnly() {
        assertTrue(Text.matches("okay go now", "go", true))
        assertFalse(Text.matches("going home", "go", true))
        assertTrue(Text.matches("GO!", "go", true))
        assertTrue(Text.matches("stand by now", "stand by", true))
    }

    @Test fun looseMatchIgnoresAccentsAndCase() {
        assertTrue(Text.matches("Café open", "cafe", false))
        assertTrue(Text.matches("going", "go", false))
    }

    @Test fun slugsAndSplits() {
        assertEquals("stand-by", Text.slug("Stand By!"))
        assertEquals(listOf("go", "stand by"), Text.split(" go , stand by ,, "))
    }

    @Test fun offensiveMask() {
        assertEquals("what the ••••", Offensive.mask("what the shit"))
    }
}

class AddressTests {
    @Test fun parses() {
        assertEquals(ManualAddress.Parsed("203.0.113.5", 47800), ManualAddress.parse("203.0.113.5"))
        assertEquals(ManualAddress.Parsed("203.0.113.5", 9000), ManualAddress.parse(" 203.0.113.5:9000 "))
        assertEquals(ManualAddress.Parsed("fe80::1", 47800), ManualAddress.parse("[fe80::1]"))
        assertEquals(ManualAddress.Parsed("fe80::1", 1234), ManualAddress.parse("[fe80::1]:1234"))
        assertEquals(ManualAddress.Parsed("studio.example.org", 47800), ManualAddress.parse("studio.example.org"))
    }

    @Test fun rejects() {
        assertNull(ManualAddress.parse(""))
        assertNull(ManualAddress.parse("not an address"))
        assertNull(ManualAddress.parse("host:0"))
        assertNull(ManualAddress.parse("host:99999"))
        assertNull(ManualAddress.parse("host:abc"))
        assertNull(ManualAddress.parse("[::1"))
    }

    @Test fun normalises() {
        assertEquals("1.2.3.4:47800", ManualAddress.normalised("1.2.3.4"))
        assertEquals("[::1]:47800", ManualAddress.normalised("[::1]"))
    }
}

/** The JSON that crosses the wire must be what Swift's JSONEncoder/Decoder produce and accept. */
class WireFormatTests {
    private val swiftHello = """{"type":"hello","peerID":"11111111-1111-1111-1111-111111111111","peerName":"Host Mac","session":"Friday",
        "channels":[{"id":"E621E1F8-C36C-495A-93FC-0C247A3E7C6D","name":"Stage 1","colorHex":"FF5F4A","localeID":"en-US",
        "groupID":"0A0A0A0A-0000-0000-0000-000000000001","groupName":"Audio"}]}""".replace("\n", "")

    @Test fun decodesAHelloFromTheMac() {
        val f = NetFrame.decode(swiftHello.toByteArray())!!
        assertEquals("hello", f.type)
        assertEquals("Friday", f.session)
        val c = f.channels!!.single()
        assertEquals("Stage 1", c.name)
        assertEquals(UUID.fromString("e621e1f8-c36c-495a-93fc-0c247a3e7c6d"), c.id)
        assertEquals("Audio", c.groupName)
        assertNull(f.join)
    }

    @Test fun decodesAMessageWithSwiftDates() {
        val json = """{"type":"message","message":{"id":"A1A1A1A1-0000-0000-0000-000000000001","channelID":"E621E1F8-C36C-495A-93FC-0C247A3E7C6D",
            "speaker":"Stage 1","text":"hello","date":"2026-10-15T08:30:00Z","typed":false}}""".replace("\n", "")
        val m = NetFrame.decode(json.toByteArray())!!.message!!
        assertEquals(Instant.parse("2026-10-15T08:30:00Z"), m.date)
    }

    @Test fun writesUppercaseUUIDsAndWholeSecondDates() {
        val id = UUID.randomUUID()
        val m = SharedMessage(id, id, "me", "hi", Instant.parse("2026-10-15T08:30:00.123456Z"), true)
        val text = String(NetFrame(type = "message", message = m).encode())
        assertTrue(text.endsWith("\n"))
        assertTrue(text, text.contains("\"date\":\"2026-10-15T08:30:00Z\""))
        assertTrue(text, text.contains("\"id\":\"${id.toString().uppercase()}\""))
        assertFalse("nulls are omitted, as Swift does", text.contains("null"))
    }

    @Test fun joinRequestHasTheFlag() {
        val text = String(NetFrame(type = "hello", peerID = "X", peerName = "Phone", session = "", join = true).encode())
        assertTrue(text, text.contains("\"join\":true"))
        assertTrue(text, text.contains("\"session\":\"\""))
    }

    @Test fun ignoresUnknownFramesAndFields() {
        val f = NetFrame.decode("""{"type":"ping","future":{"a":1}}""".toByteArray())
        assertNotNull(f)
        assertEquals("ping", f!!.type)
        assertNull(NetFrame.decode("not json".toByteArray()))
    }

    @Test fun readsTheMacsConfigFile() {
        val text = javaClass.getResource("/mac-config.json")!!.readText()
        val cfg = CueJson.decodeFromString(SavedConfig.serializer(), text)
        assertEquals("Friday", cfg.settings.sessionName)
        assertTrue(cfg.channels.isNotEmpty())
        // and back: the Mac must still be able to read what we write.
        val again = CueJson.decodeFromString(SavedConfig.serializer(), CueJson.encodeToString(SavedConfig.serializer(), cfg))
        assertEquals(cfg, again)
    }

    @Test fun setupFilesRoundTrip() {
        val b = ConfigBundle(
            channels = listOf(Channel(name = "FOH", keywords = listOf("more"))), groups = listOf(ChannelGroup(name = "Audio")),
            automations = listOf(Automation(name = "Cue: go", isCue = true, trigger = AutomationTrigger(phrase = "go"))),
            settings = AppSettings(displayName = "Phone"),
        )
        val text = CueJson.encodeToString(ConfigBundle.serializer(), b)
        assertTrue(text, text.contains("\"flashWindow\""))      // the Mac's raw value for the action kind
        assertEquals(b, CueJson.decodeFromString(ConfigBundle.serializer(), text))
    }
}
