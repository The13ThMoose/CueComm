package no.meltveit.cuecomm.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Polls until the condition holds, or fails the test with `what`. */
fun eventually(what: String, timeoutMs: Long = 12_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching { cond() }.getOrDefault(false)) return
        Thread.sleep(50)
    }
    throw AssertionError("Timed out waiting for: $what")
}

/** One CueComm device: its own store, its own "main thread", its own folder, as if on its own phone. */
class Node(val name: String, port: Int = freePort(), session: String = "", dir: File? = null, open: Boolean = false, platform: Platform = object : Platform {}) {
    val dir: File = dir ?: Files.createTempDirectory("cuecomm-$name").toFile()
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "main-$name").apply { isDaemon = true } }
    val main = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    val store: AppStore
    val port = port

    init {
        store = runBlocking(main) {
            AppStore(this@Node.dir, name, scope, main, platform).also { s ->
                s.updateSettings { it.copy(displayName = name, listenPort = port, sessionName = session, networkEnabled = open) }
            }
        }
    }

    fun <T> on(f: AppStore.() -> T): T = runBlocking(main) { store.f() }
    val state: StoreState get() = on { state.value }
    val channels get() = state.channels
    fun stop() {
        runCatching { on { shutdown() } }
        scope.cancel()
        exec.shutdownNow()
    }
}
