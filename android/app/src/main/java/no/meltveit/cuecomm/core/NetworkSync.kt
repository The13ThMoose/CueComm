package no.meltveit.cuecomm.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as Pipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Finds other devices on the local network (Bonjour on the Mac, NSD on Android) and advertises this one. */
interface Discovery {
    /** Browse for sessions; when `advertise` is true also announce this device on `port`. `found` may be called on any thread. */
    fun start(instanceID: String, name: String, session: String, advertise: Boolean, port: Int, found: (List<PeerService>) -> Unit)
    fun stop()
}

/** This device's addresses that another device could dial: the local network and any VPN (Tailscale, WireGuard…). */
object LocalAddress {
    data class Entry(val kind: String, val ip: String)

    fun ipv4(): List<Entry> {
        val out = mutableListOf<Entry>()
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return out
        for (nif in ifs) {
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            val n = nif.name.lowercase()
            val kind = when {
                n.startsWith("wlan") || n.startsWith("wifi") -> "Wi-Fi"
                n.startsWith("eth") || n.startsWith("en") -> "Ethernet"
                n.startsWith("tun") || n.startsWith("wg") || n.startsWith("tailscale") || n.startsWith("utun") || n.startsWith("ppp") -> "VPN"
                n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("rndis") || n.startsWith("usb") -> "Hotspot or USB"
                else -> continue                           // cellular (rmnet) is not reachable from outside
            }
            for (a in nif.inetAddresses) {
                if (a !is Inet4Address || a.isLinkLocalAddress) continue
                val e = Entry(kind, a.hostAddress ?: continue)
                if (e !in out) out += e
            }
        }
        return out
    }
}

/**
 * Mesh of CueComm devices over TCP. Devices on the same network find each other through [Discovery];
 * devices on other networks are dialled by address. Peers connect only when they are in the same session
 * (an empty name is "open sharing"), and every pair keeps exactly one connection: the one dialled by the
 * device with the smaller instance ID. A listener never speaks first: the dialling side sends its hello,
 * and we answer only after its session name matches ours, so a port scan learns nothing. The one exception
 * is a join request (hello with `join`), which is answered with the session name so a device can be joined
 * by typing its address.
 *
 * Everything here runs on [main]; sockets are read and written on [io].
 */
class NetworkSync(
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private inner class Link(val socket: Socket, val outbound: Boolean, val manualKey: String?, val dialedName: String?) {
        val out = Pipe<ByteArray>(Pipe.UNLIMITED)
        var peerID: String? = null
        var peerName = "Unknown"
        var sentHello = false
        var dropped = false

        fun close() {
            out.close()
            runCatching { socket.close() }
        }
    }

    var helloChannels: () -> List<SharedChannel> = { emptyList() }
    var helloHistory: () -> List<SharedMessage> = { emptyList() }
    var onFrame: (NetFrame, String) -> Unit = { _, _ -> }
    var onPeersChanged: (List<String>) -> Unit = {}
    var onSessions: (List<DiscoveredSession>) -> Unit = {}
    var onManualStatus: (Map<String, ManualLinkState>) -> Unit = {}
    var onListenPort: (Int?) -> Unit = {}
    var onError: (String) -> Unit = {}
    var discovery: Discovery? = null
    /** Android sets keep-alive timers on the raw socket here so a dead internet path is noticed within a minute. */
    var socketTuner: (Socket) -> Unit = {}

    /** Addresses ("host:port") to dial while a session is live. Retried every few seconds until connected. */
    var manualPeers: List<String> = emptyList()
        set(value) {
            val changed = field != value
            field = value
            if (changed) syncManual()
        }

    private val links = mutableListOf<Link>()
    private val dialing = mutableSetOf<String>()
    private val pendingManual = mutableSetOf<String>()
    private val manualState = LinkedHashMap<String, ManualLinkState>()
    private val manualPeerID = mutableMapOf<String, String>()
    private var lastFound: List<PeerService> = emptyList()
    private var instanceID = ""
    private var displayName = ""
    private var session = ""
    private var port = ManualAddress.DEFAULT_PORT
    private var advertising = false
    private var generation = 0
    private var server: ServerSocket? = null
    private var tickJob: Job? = null
    private var acceptJob: Job? = null

    var isRunning = false
        private set

    // MARK: Lifecycle

    /** `advertise = false` only browses, so the Session screen can list sessions before you join one. */
    fun start(instanceID: String, name: String, session: String, advertise: Boolean, port: Int = ManualAddress.DEFAULT_PORT) {
        if (isRunning && this.instanceID == instanceID && this.session == session && advertising == advertise && this.port == port) {
            if (displayName != name) updateName(name)
            return
        }
        stop()
        this.instanceID = instanceID
        this.displayName = name
        this.session = session
        this.advertising = advertise
        this.port = port
        isRunning = true
        val g = ++generation

        if (!advertise) {
            discovery?.start(instanceID, name, session, false, 0, ::discovered)
            return
        }
        scope.launch(io) {
            val bound = bind(port)
            withContext(main) {
                if (g != generation) { bound?.close(); return@withContext }
                server = bound
                bound?.let { accept(it, g) }
                onListenPort(bound?.localPort)
                discovery?.start(instanceID, name, session, true, bound?.localPort ?: port, ::discovered)
                tickJob = scope.launch(main) {
                    while (isActive) {
                        delay(5000)
                        syncManual()
                        discovered(lastFound)
                    }
                }
                syncManual()
            }
        }
    }

    /** Listens on the chosen port so other devices can dial in. If that port is taken, falls back to any free
     *  port: discovery still works, but dialling by address needs the port shown in the Session screen. */
    private suspend fun bind(wanted: Int): ServerSocket? {
        try {
            return ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(wanted)) }
        } catch (e: Exception) {
            withContext(main) {
                onError("Port $wanted is in use, so other devices can only reach this one automatically. Pick another port in the Session screen. (${e.message})")
            }
        }
        return try {
            ServerSocket(0)
        } catch (e: Exception) {
            withContext(main) { onError("Network listener failed: ${e.message}") }
            null
        }
    }

    private fun accept(s: ServerSocket, g: Int) {
        acceptJob = scope.launch(io) {
            while (isActive) {
                val c = try { s.accept() } catch (e: Exception) { break }
                withContext(main) {
                    if (g != generation) runCatching { c.close() } else adopt(c, outbound = false)
                }
            }
        }
    }

    fun stop() {
        generation++
        tickJob?.cancel(); tickJob = null
        acceptJob?.cancel(); acceptJob = null
        runCatching { server?.close() }; server = null
        discovery?.stop()
        links.toList().forEach { it.close() }
        links.clear()
        dialing.clear(); pendingManual.clear()
        manualState.clear(); manualPeerID.clear()
        lastFound = emptyList()
        advertising = false
        isRunning = false
        onPeersChanged(emptyList())
        onSessions(emptyList())
        onManualStatus(emptyMap())
        onListenPort(null)
    }

    fun updateName(name: String) {
        displayName = name
        // The advertised name refreshes on the next restart; peers learn the new one from a fresh hello.
        broadcast(hello())
    }

    // MARK: Discover & connect

    /** Called by [Discovery] with everything currently visible; may come from any thread. */
    fun discovered(results: List<PeerService>) {
        scope.launch(main) { found(results) }
    }

    private fun found(results: List<PeerService>) {
        if (!isRunning) return
        // CUECOMM_NO_DISCOVERY lets a test prove that dialling by address works without Bonjour.
        if (System.getenv("CUECOMM_NO_DISCOVERY") != null) return
        lastFound = results
        val bySession = linkedMapOf<String, MutableList<String>>()
        for (r in results) {
            if (r.session.isNotEmpty()) bySession.getOrPut(r.session) { mutableListOf() } += if (r.name == instanceID) displayName else r.display
            if (!advertising || r.name == instanceID || r.session != session) continue
            // Only the smaller ID dials, so each pair has exactly one connection.
            if (instanceID >= r.name || r.name in dialing || links.any { it.peerID == r.name }) continue
            dialing += r.name
            dial(r.host, r.port, manualKey = null, dialedName = r.name)
        }
        onSessions(bySession.map { DiscoveredSession(it.key, it.value.sorted()) }.sortedBy { it.name })
    }

    // MARK: Manual addresses

    private fun publishManual() = onManualStatus(LinkedHashMap(manualState))

    /** Dial every listed address that has no live or pending connection; forget and drop the ones removed. */
    private fun syncManual() {
        if (!advertising || !isRunning) return
        val keys = manualPeers.mapNotNull { ManualAddress.normalised(it) }.distinct()
        for (k in manualState.keys.toList()) if (k !in keys) { manualState.remove(k); manualPeerID.remove(k) }
        for (l in links.toList()) if (l.manualKey != null && l.manualKey !in keys) l.close()

        for (k in keys) {
            val pid = manualPeerID[k]
            val live = pid?.let { id -> links.firstOrNull { it.peerID == id } }
            if (live != null) { manualState[k] = ManualLinkState.Connected(live.peerName); continue }
            manualPeerID.remove(k)
            if (k in pendingManual || links.any { it.manualKey == k }) continue
            val a = ManualAddress.parse(k) ?: continue
            if (manualState[k] == null) manualState[k] = ManualLinkState.Connecting
            dial(a.host, a.port, manualKey = k, dialedName = null)
        }
        publishManual()
    }

    private fun dial(host: String, port: Int, manualKey: String?, dialedName: String?) {
        if (manualKey != null) pendingManual += manualKey
        val g = generation
        scope.launch(io) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                runCatching { s.close() }
                withContext(main) {
                    if (g != generation) return@withContext
                    if (dialedName != null) dialing -= dialedName
                    if (manualKey != null) {
                        pendingManual -= manualKey
                        if (manualPeerID[manualKey] == null) { manualState[manualKey] = ManualLinkState.Failed(describe(e)); publishManual() }
                    }
                }
                return@launch
            }
            withContext(main) {
                if (manualKey != null) pendingManual -= manualKey
                if (g != generation) runCatching { s.close() } else adopt(s, outbound = true, dialedName = dialedName, manualKey = manualKey)
            }
        }
    }

    private fun adopt(socket: Socket, outbound: Boolean, dialedName: String? = null, manualKey: String? = null) {
        runCatching { socket.tcpNoDelay = true; socket.keepAlive = true; socketTuner(socket) }
        val link = Link(socket, outbound, manualKey, dialedName)
        links += link

        // Writer: frames queued by send() go out in order.
        scope.launch(io) {
            try {
                val os = socket.getOutputStream()
                for (b in link.out) { os.write(b); os.flush() }
                runCatching { socket.shutdownOutput() }     // a closed queue is a clean goodbye (join answers)
            } catch (e: Exception) {
                runCatching { socket.close() }
            }
        }
        // Reader: lines are decoded here and handled on main, in order.
        scope.launch(io) {
            var reason: String? = null
            try {
                val input = socket.getInputStream()
                val buf = ByteArray(16384)
                val pending = ByteArrayOutputStream()
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var start = 0
                    for (i in 0 until n) {
                        if (buf[i] == NL) {
                            pending.write(buf, start, i - start)
                            val line = pending.toByteArray()
                            pending.reset()
                            start = i + 1
                            val frame = NetFrame.decode(line)
                            if (frame != null) withContext(main) { if (!link.dropped) handle(frame, link) }
                        }
                    }
                    pending.write(buf, start, n - start)
                    if (pending.size() > MAX_BUFFER) break
                }
            } catch (e: Exception) {
                reason = describe(e)
            }
            withContext(main) { drop(link, reason) }
        }

        if (outbound) sendHello(link)
        else scope.launch(main) {
            delay(HELLO_TIMEOUT_MS)
            if (link.peerID == null && !link.dropped) link.close()
        }
    }

    private fun drop(link: Link, reason: String?) {
        if (link.dropped) return
        link.dropped = true
        link.close()
        link.dialedName?.let { dialing -= it }
        links -= link
        val k = link.manualKey
        if (k != null) {
            val stillConnected = manualPeerID[k]?.let { pid -> links.any { it.peerID == pid } } ?: false
            if (!stillConnected) {
                manualPeerID.remove(k)
                if (manualState[k] != null) {
                    manualState[k] = ManualLinkState.Failed(
                        reason ?: if (link.peerID == null) "The other device closed the connection. Check that it is in the same session."
                        else "Connection lost. Trying again.",
                    )
                }
            }
            publishManual()
        }
        publishPeers()
    }

    // MARK: Frames

    private fun hello() = NetFrame(type = "hello", peerID = instanceID, peerName = displayName, session = session, channels = helloChannels())

    private fun sendHello(link: Link) {
        link.sentHello = true
        send(hello(), link)
    }

    fun broadcast(frame: NetFrame) {
        val bytes = frame.encode()
        for (l in links) if (l.peerID != null) l.out.trySend(bytes)
    }

    private fun send(frame: NetFrame, link: Link) {
        link.out.trySend(frame.encode())
    }

    private fun handle(frame: NetFrame, link: Link) {
        if (frame.type == "hello" && frame.join == true) {
            // Someone is joining by address and cannot know our session name yet. Tell them, then let go;
            // they reconnect as an ordinary member. Only a device that is hosting answers.
            if (link.outbound || !advertising) { link.close(); return }
            send(NetFrame(type = "welcome", peerID = instanceID, peerName = displayName, session = session, join = true), link)
            link.out.close()
            return
        }
        if (frame.type == "hello") {
            val id = frame.peerID
            if (id == null) { link.close(); return }
            if (id == instanceID) {                                    // dialled our own address
                link.manualKey?.let { manualState[it] = ManualLinkState.Failed("That address is this device."); publishManual() }
                link.close(); return
            }
            if ((frame.session ?: "") != session) { link.close(); return }

            link.manualKey?.let { manualPeerID[it] = id }
            // Two connections to one peer (both sides dialled): keep the one dialled by the smaller ID, on both ends.
            val existing = links.firstOrNull { it !== link && it.peerID == id }
            if (existing != null) {
                val mine = if (link.outbound) instanceID else id
                val theirs = if (existing.outbound) instanceID else id
                if (mine < theirs) {
                    existing.peerID = null
                    existing.close()
                } else {
                    link.manualKey?.let { manualState[it] = ManualLinkState.Connected(existing.peerName); publishManual() }
                    link.close(); return
                }
            }
            val first = link.peerID == null
            link.peerID = id
            link.peerName = frame.peerName ?: "Peer"
            if (!link.sentHello) sendHello(link)
            link.manualKey?.let { manualState[it] = ManualLinkState.Connected(link.peerName); publishManual() }
            publishPeers()
            // New arrivals catch up on what was said so far.
            if (first) {
                val history = helloHistory()
                if (history.isNotEmpty()) send(NetFrame(type = "history", messages = history), link)
            }
        }
        if (link.peerID == null) return
        onFrame(frame, link.peerName)
    }

    private fun publishPeers() = onPeersChanged(links.filter { it.peerID != null }.map { it.peerName }.sorted())

    companion object {
        private const val NL = '\n'.code.toByte()
        private const val MAX_BUFFER = 8 shl 20
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val HELLO_TIMEOUT_MS = 10_000L

        fun describe(e: Throwable): String = when (e) {
            is ConnectException ->
                if (e.message?.contains("refused", true) == true) "Refused. Is CueComm running there, and is this the right port?"
                else "Cannot reach that address from here."
            is SocketTimeoutException -> "No answer. Check the address, the firewall and port forwarding."
            is NoRouteToHostException -> "Cannot reach that address from here."
            is UnknownHostException -> "That address was not found."
            is SocketException ->
                if (e.message?.contains("unreachable", true) == true) "Cannot reach that address from here."
                else e.message ?: "Connection lost."
            else -> e.message ?: e.javaClass.simpleName
        }

        /**
         * One question to a listening CueComm: "which session are you in?" Joining by address needs it because
         * a listener stays silent until the caller already knows the session name.
         */
        suspend fun probe(
            host: String, port: Int, ownID: String, ownName: String,
            tuner: (Socket) -> Unit = {}, timeoutMs: Int = 8000,
        ): Result<Answer> = withContext(Dispatchers.IO) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), timeoutMs)
                runCatching { s.tcpNoDelay = true; tuner(s) }
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(NetFrame(type = "hello", peerID = ownID, peerName = ownName, session = "", join = true).encode()); flush() }

                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                val input = s.getInputStream()
                val buf = ByteArray(8192)
                val pending = ByteArrayOutputStream()
                while (System.nanoTime() < deadline) {
                    val n = input.read(buf)
                    if (n < 0) return@withContext Result.failure(
                        ProbeFailure("The other device closed the connection without answering. Check that it runs the latest CueComm and is hosting a session."),
                    )
                    pending.write(buf, 0, n)
                    if (pending.size() > 1 shl 20) return@withContext Result.failure(ProbeFailure("That address did not answer like CueComm."))
                    val bytes = pending.toByteArray()
                    var start = 0
                    for (i in bytes.indices) {
                        if (bytes[i] != NL) continue
                        val f = NetFrame.decode(bytes.copyOfRange(start, i))
                        start = i + 1
                        if (f != null && f.type == "welcome" && f.peerID != null) {
                            return@withContext Result.success(Answer(f.peerID, f.peerName ?: "Device", f.session ?: ""))
                        }
                    }
                }
                Result.failure(ProbeFailure("No answer. Check the address, the firewall and port forwarding."))
            } catch (e: Exception) {
                Result.failure(ProbeFailure(describe(e)))
            } finally {
                runCatching { s.close() }
            }
        }
    }
}

class ProbeFailure(message: String) : Exception(message)
