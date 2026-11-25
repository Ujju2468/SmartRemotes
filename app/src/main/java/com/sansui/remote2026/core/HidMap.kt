package com.sansui.remote2026.core

/** Bluetooth HID backup: maps Android key codes / characters to HID reports. */
object HidMap {
    const val KBD = 1      // report id 1: keyboard (8 bytes)
    const val CONSUMER = 2 // report id 2: consumer control (2 bytes, little endian)

    // Keyboard + consumer-control report descriptor (what the phone announces to the TV).
    val DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()

    class Report(val id: Int, val data: ByteArray)

    private fun kbd(usage: Int, mod: Int = 0) = Report(KBD, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
    private fun cons(usage: Int) = Report(CONSUMER, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
    val releaseKbd = Report(KBD, ByteArray(8))
    val releaseCons = Report(CONSUMER, ByteArray(2))

    fun forKey(code: Int): Report? = when (code) {
        19 -> kbd(0x52); 20 -> kbd(0x51); 21 -> kbd(0x50); 22 -> kbd(0x4F)   // arrows
        23, 66 -> kbd(0x28)                                                 // OK / Enter
        67 -> kbd(0x2A)                                                     // Backspace
        in 7..16 -> kbd(if (code == 7) 0x27 else 0x1E + (code - 8))         // 0-9
        4 -> cons(0x224); 3 -> cons(0x223); 82 -> cons(0x40)                // Back, Home, Menu
        24 -> cons(0xE9); 25 -> cons(0xEA); 164 -> cons(0xE2)               // Vol +, -, mute
        85 -> cons(0xCD); 87 -> cons(0xB5); 88 -> cons(0xB6)                // play/pause, next, prev
        90 -> cons(0xB3); 89 -> cons(0xB4); 86 -> cons(0xB7)                // ff, rew, stop
        26 -> cons(0x30); 84 -> cons(0x221); 172 -> cons(0x8D)              // power, search/assistant, guide
        175 -> cons(0x61); 165 -> cons(0x1BD)                               // captions, info
        else -> null
    }

    fun forChar(c: Char): Report? = when (c) {
        in 'a'..'z' -> kbd(0x04 + (c - 'a'))
        in 'A'..'Z' -> kbd(0x04 + (c - 'A'), 0x02)
        in '1'..'9' -> kbd(0x1E + (c - '1'))
        '0' -> kbd(0x27); ' ' -> kbd(0x2C); '-' -> kbd(0x2D); '.' -> kbd(0x37)
        ',' -> kbd(0x36); '/' -> kbd(0x38); '\'' -> kbd(0x34); '@' -> kbd(0x1F, 0x02)
        else -> null
    }

    fun release(r: Report) = if (r.id == KBD) releaseKbd else releaseCons
}
