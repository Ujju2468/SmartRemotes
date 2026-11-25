package com.sansui.remote2026.core

import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class WrongCodeException : Exception("Wrong code")

/**
 * Android TV pairing (port 6467). Flow: begin() makes the TV show a 6-character code,
 * finish(code) proves we typed it. A mistyped code is detected locally and can be retried.
 */
class PairingSession(
    private val host: String,
    private val id: Identity,
    private val clientName: String,
    private val port: Int = 6467
) {
    private var sock: SSLSocket? = null

    private fun outer(build: PW.() -> Unit): ByteArray =
        PW().int(1, 2).int(2, 200).apply(build).framed()

    private fun send(b: ByteArray) { sock!!.getOutputStream().apply { write(b); flush() } }
    private fun read(): List<PR.Field> {
        val f = PR(readFrame(sock!!.getInputStream())).fields()
        val status = f.num(2) ?: 200L
        if (status != 200L) throw IOException("TV refused pairing (status $status)")
        return f
    }

    fun begin() {
        sock = id.connect(host, port, 5000, 12000)
        send(outer { msg(10) { str(1, "atvremote"); str(2, clientName) } })
        while (true) {
            val m = read()
            when {
                m.has(11) -> send(outer {
                    msg(20) { msg(1) { int(1, 3); int(2, 6) }; int(3, 1) }   // hex, 6 symbols, role=input
                })
                m.has(20) -> send(outer {
                    msg(30) { msg(1) { int(1, 3); int(2, 6) }; int(2, 1) }
                })
                m.has(31) -> return                                          // TV now shows the code
                else -> throw IOException("Unexpected pairing message")
            }
        }
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    fun finish(codeIn: String) {
        val code = codeIn.trim().uppercase()
        if (!Regex("[0-9A-F]{6}").matches(code)) throw WrongCodeException()
        val me = id.cert.publicKey as RSAPublicKey
        val tv = sock!!.session.peerCertificates[0].publicKey as RSAPublicKey
        val tail = ByteArray(2) { code.substring(2 + it * 2, 4 + it * 2).toInt(16).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(unsigned(me.modulus)); update(unsigned(me.publicExponent))
            update(unsigned(tv.modulus)); update(unsigned(tv.publicExponent))
            update(tail); digest()
        }
        if ((hash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) throw WrongCodeException()
        send(outer { msg(40) { bytes(1, hash) } })
        while (true) {
            val m = read()
            if (m.has(41)) return
            if (!m.has(11) && !m.has(20) && !m.has(31)) throw IOException("Unexpected pairing reply")
        }
    }

    fun close() { try { sock?.close() } catch (_: Exception) {} }
}
