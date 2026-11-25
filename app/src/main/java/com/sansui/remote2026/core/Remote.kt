package com.sansui.remote2026.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** One live control connection to the TV (port 6466). */
class RemoteSession(
    private val host: String,
    private val id: Identity,
    private val listener: Listener,
    private val port: Int = 6466
) {
    interface Listener {
        fun onPower(on: Boolean)
        fun onVolume(level: Int, max: Int, muted: Boolean)
        fun onApp(pkg: String)
        fun onIme(active: Boolean)
        fun onClosed(session: RemoteSession, reason: String)
    }

    @Volatile var model = ""; private set
    @Volatile var vendor = ""; private set
    @Volatile var closed = false; private set

    private var sock: SSLSocket? = null
    private val lock = Any()
    private val started = CountDownLatch(1)
    private var supported = 0

    // PING | KEY | IME | POWER | VOLUME | APP_LINK  (IME makes the TV tell us when a text field is focused)
    private val wanted = 1 or 2 or 4 or 32 or 64 or 512

    @Volatile var imeActive = false; private set
    @Volatile private var imeCounter = 0
    @Volatile private var imeField = 0

    /** Connects and waits until the TV says it is ready. Throws on failure. */
    fun connect() {
        sock = id.connect(host, port, 4000, 16000)   // TV pings every ~5s; silence for 16s = dead
        Thread({ readLoop() }, "tv-reader").apply { isDaemon = true; start() }
        if (!started.await(8, TimeUnit.SECONDS)) {
            close("TV did not respond")
            throw IOException("TV did not respond")
        }
        if (closed) throw IOException("Connection closed during setup")
    }

    private fun send(b: ByteArray) {
        synchronized(lock) {
            val o = sock?.getOutputStream() ?: throw IOException("Not connected")
            o.write(b); o.flush()
        }
    }

    private fun readLoop() {
        var why = "Connection closed"
        try {
            val inp = sock!!.getInputStream()
            while (!closed) {
                val m = PR(readFrame(inp)).fields()
                when {
                    m.has(1) -> {                                   // remote_configure
                        val cfg = m.sub(1)!!
                        supported = (cfg.num(1) ?: 0L).toInt()
                        cfg.sub(2)?.let { vendor = it.text(2) ?: ""; model = it.text(1) ?: "" }
                        send(PW().msg(1) {
                            int(1, supported and wanted)
                            msg(2) { int(3, 1); str(4, "1"); str(5, "atvremote"); str(6, "1.0.0") }
                        }.framed())
                    }
                    m.has(2) -> send(PW().msg(2) { int(1, supported and wanted) }.framed())
                    m.has(8) -> {                                   // ping -> pong
                        val v = m.sub(8)?.num(1) ?: 0L
                        send(PW().msg(9) { int(1, v) }.framed())
                    }
                    m.has(40) -> {                                  // remote_start: ready + power state
                        listener.onPower((m.sub(40)?.num(1) ?: 0L) == 1L)
                        started.countDown()
                    }
                    m.has(50) -> m.sub(50)?.let {
                        listener.onVolume((it.num(7) ?: 0L).toInt(), (it.num(6) ?: 100L).toInt(), (it.num(8) ?: 0L) == 1L)
                    }
                    m.has(20) -> {                                  // TV: a text field got focus / app changed
                        val inj = m.sub(20)!!
                        val app = inj.sub(1)
                        app?.text(12)?.let { if (it.isNotEmpty()) listener.onApp(it) }
                        imeCounter = (app?.num(1) ?: 0L).toInt()
                        imeField = (inj.sub(2)?.num(1) ?: 0L).toInt()
                        imeActive = imeField != 0
                        listener.onIme(imeActive)
                    }
                    m.has(22) -> {                                  // TV asks to show the keyboard
                        m.sub(22)?.sub(2)?.num(1)?.let { imeField = it.toInt() }
                        imeActive = true; listener.onIme(true)
                    }
                    m.has(21) -> m.sub(21)?.let {                   // TV echo of the latest counters
                        it.num(1)?.let { c -> imeCounter = c.toInt() }
                        it.num(2)?.let { f -> imeField = f.toInt() }
                    }
                    // 3 = remote_error: nothing useful for us
                }
            }
        } catch (e: Exception) {
            why = e.message ?: e.javaClass.simpleName
        }
        close(why)
    }

    fun key(code: Int, direction: Int = 3) = send(PW().msg(10) { int(1, code); int(2, direction) }.framed())
    /** Inserts text into the TV's focused text field (the official way; needs imeActive). */
    fun text(t: String) = send(PW().msg(21) {
        int(1, imeCounter); int(2, imeField)
        msg(3) { int(1, 1); msg(2) { int(1, t.length); int(2, t.length); str(3, t) } }
    }.framed())
    fun launch(link: String) = send(PW().msg(90) { str(1, link) }.framed())

    fun close(reason: String = "Closed") {
        val first: Boolean
        synchronized(lock) { first = !closed; closed = true }
        try { sock?.close() } catch (_: Exception) {}
        started.countDown()
        if (first) listener.onClosed(this, reason)
    }
}
