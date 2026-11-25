package com.sansui.remote2026.core

import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The phone's identity towards the TV: a self-signed RSA-2048 certificate.
 * The TV remembers this certificate after pairing, so it must persist across launches.
 */
class Identity(val key: PrivateKey, val cert: X509Certificate) {

    /** TLS context that always presents our certificate and accepts the TV's self-signed one. */
    val ssl: SSLContext by lazy {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<Principal>?) = arrayOf("c")
            override fun chooseClientAlias(t: Array<String>?, i: Array<Principal>?, s: Socket?) = "c"
            override fun chooseEngineClientAlias(t: Array<String>?, i: Array<Principal>?, e: SSLEngine?) = "c"
            override fun getServerAliases(t: String?, i: Array<Principal>?): Array<String>? = null
            override fun chooseServerAlias(t: String?, i: Array<Principal>?, s: Socket?): String? = null
            override fun getCertificateChain(a: String?) = arrayOf(cert)
            override fun getPrivateKey(a: String?) = key
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf<TrustManager>(tm), SecureRandom()) }
    }

    fun connect(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = connectTimeoutMs
            val s = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            s.useClientMode = true
            s.startHandshake()
            s.soTimeout = readTimeoutMs
            return s
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    companion object {
        fun loadOrCreate(dir: File): Identity {
            val kf = File(dir, "client.key"); val cf = File(dir, "client.crt")
            if (kf.exists() && cf.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(cf.inputStream()) as X509Certificate
                    return Identity(key, cert)
                } catch (_: Exception) { /* corrupt: regenerate below */ }
            }
            val id = create()
            dir.mkdirs()
            kf.writeBytes(id.key.encoded); cf.writeBytes(id.cert.encoded)
            return id
        }

        fun create(commonName: String = "atvremote"): Identity {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val sigAlg = seq(oid(1, 2, 840, 113549, 1, 1, 11), tlv(0x05, ByteArray(0)))
            val name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0C, commonName.toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = seq(utc(Date(now - 86_400_000L)), utc(Date(now + 3650L * 86_400_000L)))
            val basic = seq(oid(2, 5, 29, 19), tlv(0x04, seq(tlv(0x01, byteArrayOf(-1)), tlv(0x02, byteArrayOf(0)))))
            val san = seq(oid(2, 5, 29, 17), tlv(0x04, seq(tlv(0x82, commonName.toByteArray()))))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val tbs = seq(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))),   // version v3
                tlv(0x02, serial.toByteArray()),
                sigAlg, name, validity, name,
                kp.public.encoded,                        // SubjectPublicKeyInfo, already DER
                tlv(0xA3, seq(basic, san))
            )
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(kp.private); update(tbs); sign()
            }
            val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            return Identity(kp.private, cert)
        }

        // ---- tiny DER encoder ----
        private fun len(n: Int): ByteArray = when {
            n < 128 -> byteArrayOf(n.toByte())
            n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        private fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + len(body.size) + body
        private fun seq(vararg p: ByteArray) = tlv(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
        private fun set(vararg p: ByteArray) = tlv(0x31, p.fold(ByteArray(0)) { a, b -> a + b })
        private fun utc(d: Date): ByteArray {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, f.format(d).toByteArray())
        }
        private fun oid(vararg n: Int): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            o.write(n[0] * 40 + n[1])
            for (i in 2 until n.size) {
                var v = n[i]; val chunk = ArrayList<Int>()
                chunk.add(v and 0x7F); v = v shr 7
                while (v > 0) { chunk.add((v and 0x7F) or 0x80); v = v shr 7 }
                for (c in chunk.reversed()) o.write(c)
            }
            return tlv(0x06, o.toByteArray())
        }
    }
}
