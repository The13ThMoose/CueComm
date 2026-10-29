@file:UseSerializers(UUIDSerializer::class, InstantSerializer::class)

package no.meltveit.cuecomm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

@Serializable
data class SharedChannel(
    val id: UUID,
    val name: String,
    val colorHex: String,
    val groupID: UUID? = null,
    val groupName: String? = null,
    val localeID: String = "en-US",
)

@Serializable
data class SharedMessage(
    val id: UUID,
    val channelID: UUID,
    val speaker: String,
    val text: String,
    val date: Instant,
    val typed: Boolean = false,
)

/** A cue that fired on one device and should be shown on the others. */
@Serializable
data class SharedCue(val title: String, val channel: String, val colorHex: String)

/**
 * Newline-delimited JSON frames exchanged between CueComm devices (Mac and Android speak the same ones).
 * type: hello | welcome | channels | message | history | removed | cue. Unknown types are ignored, so
 * either side can add frames without breaking the other.
 */
@Serializable
data class NetFrame(
    val type: String,
    val peerID: String? = null,
    val peerName: String? = null,
    val session: String? = null,
    val channels: List<SharedChannel>? = null,
    val message: SharedMessage? = null,
    val messages: List<SharedMessage>? = null,
    val removedID: UUID? = null,
    val cue: SharedCue? = null,
    val join: Boolean? = null,          // hello only: "I am joining by address, tell me your session"
) {
    fun encode(): ByteArray = (CueJson.encodeToString(serializer(), this) + "\n").toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(line: ByteArray): NetFrame? =
            runCatching { CueJson.decodeFromString(serializer(), String(line, Charsets.UTF_8)) }.getOrNull()
    }
}

/** Where a manually entered address stands. */
sealed class ManualLinkState {
    data object Connecting : ManualLinkState()
    data class Connected(val name: String) : ManualLinkState()
    data class Failed(val why: String) : ManualLinkState()
}

/** Progress of "join by address" in the Session screen. */
sealed class AddressJoin {
    data object Idle : AddressJoin()
    data class Joining(val address: String) : AddressJoin()
    data class Failed(val why: String) : AddressJoin()
}

data class DiscoveredSession(val name: String, val members: List<String>)

/** Parsing for addresses typed by hand: "host", "host:port" or "[ipv6]:port". */
object ManualAddress {
    const val DEFAULT_PORT = 47800

    data class Parsed(val host: String, val port: Int)

    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        var host = t
        var portText: String? = null
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close < 0) return null
            host = t.substring(1, close)
            val rest = t.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                portText = rest.substring(1)
            }
        } else if (t.count { it == ':' } == 1) {
            val colon = t.indexOf(':')
            host = t.substring(0, colon)
            portText = t.substring(colon + 1)
        }
        if (host.isEmpty()) return null
        var port = DEFAULT_PORT
        if (portText != null) {
            val p = portText.toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            port = p
        }
        return Parsed(host, port)
    }

    /** The stored form, always with a port. */
    fun normalised(text: String): String? {
        val a = parse(text) ?: return null
        return if (a.host.contains(':')) "[${a.host}]:${a.port}" else "${a.host}:${a.port}"
    }
}

/** A device found by discovery (Bonjour/NSD). `name` is the other device's instance ID. */
data class PeerService(val name: String, val host: String, val port: Int, val session: String, val display: String)

/** One device's answer to "which session are you in?", used to join by address. */
data class Answer(val peerID: String, val name: String, val session: String)
