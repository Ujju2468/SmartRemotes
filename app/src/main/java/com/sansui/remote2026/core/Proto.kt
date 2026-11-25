package com.sansui.remote2026.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Minimal protobuf writer: just what the Android TV Remote v2 protocol needs. */
class PW {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
    fun int(field: Int, v: Int) = int(field, v.toLong())
    fun bytes(field: Int, b: ByteArray) = apply {
        varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b)
    }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, build: PW.() -> Unit) = bytes(field, PW().apply(build).toBytes())
    fun toBytes(): ByteArray = out.toByteArray()

    /** Length-delimited frame, as sent on the wire. */
    fun framed(): ByteArray {
        val body = toBytes()
        val o = PW()
        o.varint(body.size.toLong())
        return o.out.toByteArray() + body
    }
}

/** Minimal protobuf reader. */
class PR(private val b: ByteArray) {
    class Field(val num: Int, val v: Long, val data: ByteArray?)

    fun fields(): List<Field> {
        val res = ArrayList<Field>()
        var pos = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                if (pos >= b.size || shift > 63) throw IOException("bad varint")
                val x = b[pos++].toInt()
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        while (pos < b.size) {
            val tag = varint()
            val num = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> res.add(Field(num, varint(), null))
                2 -> {
                    val n = varint().toInt()
                    if (n < 0 || pos + n > b.size) throw IOException("bad length")
                    res.add(Field(num, 0, b.copyOfRange(pos, pos + n))); pos += n
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> throw IOException("bad wire type")
            }
        }
        return res
    }
}

fun List<PR.Field>.sub(num: Int): List<PR.Field>? =
    firstOrNull { it.num == num && it.data != null }?.let { PR(it.data!!).fields() }
fun List<PR.Field>.has(num: Int) = any { it.num == num }
fun List<PR.Field>.num(num: Int): Long? = firstOrNull { it.num == num && it.data == null }?.v
fun List<PR.Field>.text(num: Int): String? =
    firstOrNull { it.num == num && it.data != null }?.data?.toString(Charsets.UTF_8)

/** Reads one varint-length-prefixed message. Blocks; throws on EOF / timeout. */
fun readFrame(inp: InputStream): ByteArray {
    var len = 0; var shift = 0
    while (true) {
        val x = inp.read()
        if (x < 0) throw EOFException("connection closed")
        len = len or ((x and 0x7F) shl shift)
        if (x and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw IOException("bad frame length")
    }
    if (len < 0 || len > 1_000_000) throw IOException("frame too large")
    val buf = ByteArray(len)
    var got = 0
    while (got < len) {
        val n = inp.read(buf, got, len - got)
        if (n < 0) throw EOFException("connection closed")
        got += n
    }
    return buf
}
