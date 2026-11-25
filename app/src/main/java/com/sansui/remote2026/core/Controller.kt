package com.sansui.remote2026.core

import java.io.File
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Finds TVs on the local network (implemented with NsdManager on Android). */
interface Discovery {
    fun start(onFound: (name: String, host: String) -> Unit)
    fun stop()
}

/** Backup link used when Wi-Fi is unavailable: the phone acts as a Bluetooth keyboard/remote. */
interface BtTransport {
    fun connect(addr: String)
    fun send(code: Int): Boolean
    fun type(text: String): Boolean
}

/**
 * Owns the saved TV, the pairing flow, and the live connection.
 * Everything the UI sees comes out of [emit] as small JSON objects.
 */
class TvController(
    private val dir: File,
    private val emit: (String) -> Unit,
    private val discovery: Discovery?,
    private val clientName: String,
    private val remotePort: Int = 6466,
    private val pairPort: Int = 6467,
    private val log: (String) -> Unit = {}
) : RemoteSession.Listener {

    private val ctl = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val tx = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile private var session: RemoteSession? = null
    @Volatile private var wantConnected = false
    @Volatile private var pairingActive = false
    private var identity: Identity? = null
    private var failures = 0
    private var pending: ScheduledFuture<*>? = null
    private val codes = LinkedBlockingQueue<String>()
    private val found = LinkedHashMap<String, String>()   // host -> name

    private var tvHost: String? = null
    private var tvName: String? = null
    private var status = "none"
    private var power: Boolean? = null
    private var vol: Triple<Int, Int, Boolean>? = null
    private var error = ""

    @Volatile var bt: BtTransport? = null
    @Volatile private var btConnected = false
    private var btAddr: String? = null
    private var btName: String? = null
    private var imeActive = false
    private var curApp = ""

    private fun wifiReady() = session?.closed == false && status == "connected"
    val isConnected get() = wifiReady() || btConnected

    init {
        val f = File(dir, "tv.properties")
        if (f.exists()) runCatching {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            tvHost = p.getProperty("host"); tvName = p.getProperty("name")
            btAddr = p.getProperty("bt_addr")?.ifEmpty { null }; btName = p.getProperty("bt_name")
        }
        if (tvHost != null) status = "offline"
        ctl.execute { runCatching { identity() } }       // generate the key early (takes a moment on phones)
    }

    private fun identity(): Identity =
        identity ?: Identity.loadOrCreate(dir).also { identity = it }

    // ---------- UI -> controller ----------
    fun hello() = ctl.execute { pushState() }

    fun start() = ctl.execute { wantConnected = true; failures = 0; if (session == null) attempt() }

    fun stop() = ctl.execute {
        wantConnected = false; pending?.cancel(false)
        session?.let { session = null; it.close("paused") }
        if (tvHost != null) { status = "offline"; power = null; pushState() }
    }

    fun scanStart() {
        ctl.execute { found.clear(); pushScan() }
        discovery?.start { name, host -> ctl.execute { found[host] = name; pushScan() } }
    }
    fun scanStop() { discovery?.stop() }

    fun pairStart(host: String, name: String) {
        if (pairingActive) return
        pairingActive = true
        codes.clear()
        Thread({ runPairing(host.trim(), name) }, "tv-pair").apply { isDaemon = true; start() }
    }
    fun pairCode(code: String) { codes.offer(code) }
    fun pairCancel() { codes.offer("!cancel") }

    fun forget() = ctl.execute {
        wantConnected = false; pending?.cancel(false)
        session?.let { session = null; it.close("forgotten") }
        tvHost = null; tvName = null; btAddr = null; btName = null; btConnected = false
        status = "none"; power = null; vol = null; imeActive = false; curApp = ""
        File(dir, "tv.properties").delete()
        pushState()
    }

    fun key(code: Int, direction: Int = 3) = tx.execute {
        val s = session
        if (wifiReady() && s != null) {
            runCatching { s.key(code, direction) }.onFailure { s.close(it.message ?: "send failed") }
        } else if (btConnected) {
            if (bt?.send(code) != true) toast("That button isn't available over Bluetooth")
        } else toast("Not connected")
    }

    fun launch(link: String) = tx.execute {
        val s = session
        if (wifiReady() && s != null) {
            runCatching { s.launch(link) }.onFailure { s.close(it.message ?: "send failed") }
        } else toast(if (btConnected) "Opening apps needs Wi-Fi (Bluetooth can't do that)" else "Not connected")
    }

    /** Types into the TV. Uses the TV's real text field when it has one; otherwise key presses. */
    fun typeText(text: String, keysOnly: Boolean = false) = tx.execute {
        val s = session
        if (wifiReady() && s != null) {
            runCatching {
                if (!keysOnly && s.imeActive) s.text(text)
                else for (ch in text.lowercase()) { charKey(ch)?.let { s.key(it); Thread.sleep(25) } }
            }.onFailure { s.close(it.message ?: "send failed") }
        } else if (btConnected) { if (bt?.type(text) != true) toast("Typing over Bluetooth failed") }
        else toast("Not connected")
    }

    // ---------- Bluetooth backup ----------
    fun setBt(addr: String, name: String) = ctl.execute { btAddr = addr; btName = name; saveTv(); pushState() }
    fun forgetBt() = ctl.execute { btAddr = null; btName = null; btConnected = false; saveTv(); pushState() }
    fun btLinked(connected: Boolean) = ctl.execute { if (btConnected != connected) { btConnected = connected; pushState() } }
    private fun tryBt() { val a = btAddr ?: return; if (!btConnected) bt?.connect(a) }

    fun shutdown() { discovery?.stop(); ctl.shutdownNow(); tx.shutdownNow(); session?.close("exit") }

    // ---------- connection loop ----------
    private fun attempt() {
        val host = tvHost ?: return
        if (!wantConnected || session != null || pairingActive) return
        status = "connecting"; error = ""; pushState()
        try {
            val s = RemoteSession(host, identity(), this, remotePort)
            session = s
            s.connect()
            failures = 0; status = "connected"
            pushState()
        } catch (e: Exception) {
            session?.let { session = null; it.close("failed") }
            failures++
            status = "offline"; power = null
            error = if (failures >= 1) "TV not reachable" else ""
            log("connect failed ($failures): ${e.message}")
            pushState()
            tryBt()                                   // Wi-Fi failed: try the Bluetooth backup right away
            if (failures >= 2) rediscover()
            scheduleAttempt(minOf(1000L * (1 shl minOf(failures, 4)), 15000L))
        }
    }

    private fun scheduleAttempt(delayMs: Long) {
        pending?.cancel(false)
        if (wantConnected) pending = ctl.schedule({ attempt() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** TV got a new IP address? Look it up again by name. */
    private fun rediscover() {
        val name = tvName ?: return
        discovery?.start { n, h ->
            if (n == name) ctl.execute {
                if (h != tvHost) {
                    log("TV moved to $h"); tvHost = h; saveTv(); failures = 0
                    discovery.stop(); scheduleAttempt(0)
                }
            }
        }
        ctl.schedule({ if (!pairingActive) discovery?.stop() }, 8, TimeUnit.SECONDS)
    }

    // ---------- RemoteSession.Listener ----------
    override fun onPower(on: Boolean) { ctl.execute { power = on; pushState() } }
    override fun onVolume(level: Int, max: Int, muted: Boolean) { ctl.execute { vol = Triple(level, max, muted); pushState() } }
    override fun onApp(pkg: String) { ctl.execute { if (curApp != pkg) { curApp = pkg; pushState() } } }
    override fun onIme(active: Boolean) { ctl.execute { if (imeActive != active) { imeActive = active; pushState() } } }
    override fun onClosed(session: RemoteSession, reason: String) {
        ctl.execute {
            if (this.session === session) { imeActive = false }
            if (this.session !== session) return@execute      // stale / already replaced
            this.session = null; status = "offline"; power = null
            log("closed: $reason")
            pushState()
            if (wantConnected) scheduleAttempt(1000)
        }
    }

    // ---------- pairing ----------
    private fun runPairing(host: String, name: String) {
        var ok = false
        var p: PairingSession? = null
        try {
            ctl.submit { session?.let { session = null; it.close("pairing") }; pending?.cancel(false) }.get()
            emit("""{"t":"pair","stage":"connecting"}""")
            p = PairingSession(host, ctl.submit<Identity> { identity() }.get(), clientName, pairPort)
            p.begin()
            emit("""{"t":"pair","stage":"code"}""")
            while (true) {
                val code = codes.poll(5, TimeUnit.MINUTES) ?: throw java.io.IOException("Timed out waiting for the code")
                if (code == "!cancel") return
                try { p.finish(code); break }
                catch (_: WrongCodeException) { emit("""{"t":"pair","stage":"wrong"}""") }
            }
            ctl.submit { tvHost = host; tvName = name; saveTv(); failures = 0; status = "offline" }.get()
            ok = true
            emit("""{"t":"pair","stage":"ok"}""")
        } catch (e: Exception) {
            log("pairing failed: ${e.message}")
            emit("""{"t":"pair","stage":"fail","msg":${q(friendly(e))}}""")
        } finally {
            p?.close()
            pairingActive = false
            ctl.execute { pushState(); if (ok && wantConnected) attempt() }
        }
    }

    private fun friendly(e: Exception) = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "Can't reach the TV. Check it is on and on the same Wi-Fi."
        is javax.net.ssl.SSLException -> "TV rejected the secure connection. Try again."
        else -> e.message ?: "Pairing failed"
    }

    // ---------- helpers ----------
    private fun saveTv() {
        val p = Properties().apply {
            setProperty("host", tvHost ?: ""); setProperty("name", tvName ?: "")
            setProperty("bt_addr", btAddr ?: ""); setProperty("bt_name", btName ?: "")
        }
        dir.mkdirs(); File(dir, "tv.properties").outputStream().use { p.store(it, null) }
    }

    private fun toast(m: String) = emit("""{"t":"toast","msg":${q(m)}}""")

    private fun pushState() {
        val via = if (wifiReady()) "wifi" else if (btConnected) "bt" else ""
        val st = if (via.isNotEmpty()) "connected" else status
        val tv = if (tvHost != null) """{"name":${q(tvName ?: "TV")},"host":${q(tvHost!!)}}""" else "null"
        val v = vol?.let { """{"l":${it.first},"m":${it.second},"mute":${it.third}}""" } ?: "null"
        val b = if (btAddr != null) """{"name":${q(btName ?: "TV")},"addr":${q(btAddr!!)}}""" else "null"
        emit("""{"t":"state","status":"$st","via":"$via","tv":$tv,"bt":$b,"power":${power ?: "null"},"vol":$v,"ime":$imeActive,"app":${q(curApp)},"err":${q(error)}}""")
    }

    private fun pushScan() {
        val items = found.entries.joinToString(",") { """{"name":${q(it.value)},"host":${q(it.key)}}""" }
        emit("""{"t":"scan","list":[$items]}""")
    }

    companion object {
        fun q(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n"); c == '\r' -> b.append("\\r")
                c.code < 0x20 || c == '\u2028' || c == '\u2029' -> b.append(String.format("\\u%04x", c.code))
                else -> b.append(c)
            }
            return b.append('"').toString()
        }

        /** Android key code for a typed character, or null if the TV keyboard can't take it. */
        fun charKey(c: Char): Int? = when (c) {
            in 'a'..'z' -> 29 + (c - 'a')
            in '0'..'9' -> 7 + (c - '0')
            ' ' -> 62; '.' -> 56; ',' -> 55; '-' -> 69; '@' -> 77; '/' -> 76; '\'' -> 75
            else -> null
        }
    }
}
