package no.meltveit.cuecomm.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.regex.Pattern

sealed class OscArg {
    data class I(val v: Int) : OscArg()
    data class F(val v: Float) : OscArg()
    data class S(val v: String) : OscArg()

    val text: String
        get() = when (this) {
            is I -> v.toString()
            is F -> v.toString()
            is S -> v
        }
}

data class OscMessage(val address: String, val args: List<OscArg> = emptyList())

object OscCodec {
    fun encode(m: OscMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pad(m.address))
        out.write(pad("," + m.args.joinToString("") { when (it) { is OscArg.I -> "i"; is OscArg.F -> "f"; is OscArg.S -> "s" } }))
        for (a in m.args) {
            when (a) {
                is OscArg.I -> out.write(ByteBuffer.allocate(4).putInt(a.v).array())
                is OscArg.F -> out.write(ByteBuffer.allocate(4).putInt(java.lang.Float.floatToRawIntBits(a.v)).array())
                is OscArg.S -> out.write(pad(a.v))
            }
        }
        return out.toByteArray()
    }

    private fun pad(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = (b.size / 4 + 1) * 4                 // at least one NUL, then up to a multiple of four
        return b.copyOf(n)
    }

    /** Decodes a datagram (single message or bundle) into messages. */
    fun decode(data: ByteArray, from: Int = 0, to: Int = data.size): List<OscMessage> {
        if (to - from <= 0) return emptyList()
        val head = "#bundle".toByteArray()
        if (to - from >= head.size && head.indices.all { data[from + it] == head[it] }) {
            val out = mutableListOf<OscMessage>()
            var i = from + 16                        // "#bundle\0" + 8-byte timetag
            while (i + 4 <= to) {
                val len = readU32(data, i); i += 4
                if (len <= 0 || i + len > to) break
                out += decode(data, i, i + len)
                i += len
            }
            return out
        }
        val cur = intArrayOf(from)
        val address = readString(data, cur, to) ?: return emptyList()
        if (!address.startsWith("/")) return emptyList()
        val args = mutableListOf<OscArg>()
        val tags = readString(data, cur, to)
        if (tags != null && tags.startsWith(",")) {
            for (t in tags.drop(1)) {
                when (t) {
                    'i' -> { if (cur[0] + 4 > to) return listOf(OscMessage(address, args)); args += OscArg.I(readU32(data, cur[0])); cur[0] += 4 }
                    'f' -> { if (cur[0] + 4 > to) return listOf(OscMessage(address, args)); args += OscArg.F(java.lang.Float.intBitsToFloat(readU32(data, cur[0]))); cur[0] += 4 }
                    's' -> readString(data, cur, to)?.let { args += OscArg.S(it) }
                    'T' -> args += OscArg.I(1)
                    'F' -> args += OscArg.I(0)
                    else -> {}
                }
            }
        }
        return listOf(OscMessage(address, args))
    }

    private fun readU32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)

    private fun readString(b: ByteArray, cur: IntArray, to: Int): String? {
        val start = cur[0]
        if (start >= to) return null
        var end = start
        while (end < to && b[end] != 0.toByte()) end++
        if (end >= to) return null
        val s = String(b, start, end - start, Charsets.UTF_8)
        cur[0] = minOf(to, ((end - start) / 4 + 1) * 4 + start)
        return s
    }

    /** "1 0.5 \"hello world\"" -> [I(1), F(0.5), S("hello world")] */
    fun parseArgs(s: String): List<OscArg> {
        val tokens = mutableListOf<Pair<String, Boolean>>()
        val cur = StringBuilder()
        var quoted = false
        var wasQuoted = false
        for (ch in s) {
            if (ch == '"') { quoted = !quoted; wasQuoted = true; continue }
            if (ch == ' ' && !quoted) {
                if (cur.isNotEmpty() || wasQuoted) tokens += cur.toString() to wasQuoted
                cur.clear(); wasQuoted = false
            } else cur.append(ch)
        }
        if (cur.isNotEmpty() || wasQuoted) tokens += cur.toString() to wasQuoted
        return tokens.map { (tok, q) ->
            when {
                q -> OscArg.S(tok)
                tok.toIntOrNull() != null -> OscArg.I(tok.toInt())
                tok.toFloatOrNull() != null -> OscArg.F(tok.toFloat())
                else -> OscArg.S(tok)
            }
        }
    }

    /** Address pattern match with `*` wildcards. */
    fun matches(address: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (!p.contains('*')) return address == p
        val re = p.split("*").joinToString(".*") { Pattern.quote(it) }
        return Regex("^$re$").matches(address)
    }
}

/** UDP in and out for OSC. Sending is fire-and-forget; listening reports each datagram's messages. */
class OscService(private val scope: CoroutineScope, private val main: CoroutineDispatcher) {
    var onMessage: ((OscMessage, String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var listenPort = -1

    fun send(message: OscMessage, host: String, port: Int) {
        if (host.isEmpty() || port !in 1..65535) return
        val data = OscCodec.encode(message)
        scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { it.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port)) }
            } catch (e: Exception) {
                withContext(main) { onError?.invoke("OSC send failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun listen(port: Int) {
        if (port == listenPort && listenJob?.isActive == true) return
        stopListening()
        if (port !in 1..65535) return
        listenPort = port
        val s = try {
            DatagramSocket(port).also { socket = it }
        } catch (e: Exception) {
            onError?.invoke("Cannot listen for OSC on port $port: ${e.message}")
            listenPort = -1
            return
        }
        listenJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: Exception) { break }
                val msgs = OscCodec.decode(buf, p.offset, p.offset + p.length)
                val from = p.address?.hostAddress ?: "?"
                if (msgs.isNotEmpty()) withContext(main) { msgs.forEach { onMessage?.invoke(it, from) } }
            }
        }
    }

    fun stopListening() {
        runCatching { socket?.close() }
        socket = null
        listenJob?.cancel()
        listenJob = null
        listenPort = -1
    }
}
