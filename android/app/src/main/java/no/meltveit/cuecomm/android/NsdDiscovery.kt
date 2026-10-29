package no.meltveit.cuecomm.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import no.meltveit.cuecomm.core.Discovery
import no.meltveit.cuecomm.core.PeerService
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Bonjour-compatible discovery: advertises `_cuecomm._tcp` with the session and name in the TXT record, and
 * browses for the same type, so Android and Mac devices on one network find each other.
 */
@Suppress("DEPRECATION")
class NsdDiscovery(context: Context) : Discovery {
    private val ctx = context.applicationContext
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicast: WifiManager.MulticastLock? = null
    private var browse: NsdManager.DiscoveryListener? = null
    private var register: NsdManager.RegistrationListener? = null
    private var callback: ((List<PeerService>) -> Unit)? = null
    private var ownName: String? = null
    private val peers = ConcurrentHashMap<String, PeerService>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0

    @Synchronized
    override fun start(instanceID: String, name: String, session: String, advertise: Boolean, port: Int, found: (List<PeerService>) -> Unit) {
        stop()
        val g = ++generation
        callback = found
        runCatching {
            multicast = wifi.createMulticastLock("cuecomm").apply { setReferenceCounted(false); acquire() }
        }

        if (advertise && port > 0) {
            val info = NsdServiceInfo().apply {
                serviceName = instanceID
                serviceType = TYPE
                setPort(port)
                setAttribute("session", session)
                setAttribute("name", name)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { ownName = i.serviceName }
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            register = l
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        }

        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (g != generation || !i.serviceType.contains("_cuecomm._tcp")) return
                synchronized(this@NsdDiscovery) { queue.addLast(i) }
                resolveNext(g)
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                if (g != generation) return
                if (peers.remove(i.serviceName) != null) publish()
            }
        }
        browse = d
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, d) }
    }

    /** Older Android resolves one service at a time, so queue them. */
    @Synchronized
    private fun resolveNext(g: Int) {
        if (resolving || queue.isEmpty() || g != generation) return
        val next = queue.removeFirst()
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done(g) }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    if (g == generation) addResolved(i)
                    done(g)
                }
            })
        }.onFailure { done(g) }
    }

    @Synchronized
    private fun done(g: Int) {
        resolving = false
        if (g == generation) resolveNext(g)
    }

    private fun addResolved(i: NsdServiceInfo) {
        val name = i.serviceName ?: return
        if (name == ownName) return
        val host = pickAddress(i)?.hostAddress?.substringBefore('%') ?: return
        val txt = i.attributes ?: emptyMap()
        fun t(k: String) = txt[k]?.toString(Charsets.UTF_8) ?: ""
        peers[name] = PeerService(name, host, i.port, t("session"), t("name").ifEmpty { name })
        publish()
    }

    /** Prefer IPv4: the Mac listens on both, and link-local IPv6 needs an interface scope. */
    private fun pickAddress(i: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val all = i.hostAddresses
            return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }
        return i.host
    }

    private fun publish() {
        callback?.invoke(peers.values.sortedBy { it.name })
    }

    @Synchronized
    override fun stop() {
        generation++
        browse?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        register?.let { runCatching { nsd.unregisterService(it) } }
        browse = null; register = null; ownName = null
        peers.clear(); queue.clear(); resolving = false
        runCatching { multicast?.takeIf { it.isHeld }?.release() }
        multicast = null
        callback = null
    }

    private companion object {
        const val TYPE = "_cuecomm._tcp."
    }
}
