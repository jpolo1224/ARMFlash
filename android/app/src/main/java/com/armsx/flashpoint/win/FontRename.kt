package com.armsx.flashpoint.win

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * A TrueType font under another family name: the same outlines and metrics, with a rebuilt name
 * table (family, style, unique, full and PostScript names in English for Windows and Mac; the
 * Windows English copyright, version, trademark, maker, designer, description, URLs and licence
 * records kept) and the table directory and checksums redone around it.
 */
object FontRename {
    private val KEPT = setOf(0, 5, 7, 8, 9, 10, 11, 12, 13, 14)
    private val MAC = setOf(1, 2, 3, 4, 6)

    fun rename(font: ByteArray, family: String, style: String): ByteArray {
        val src = ByteBuffer.wrap(font)
        val version = src.getInt(0)
        val count = src.getShort(4).toInt() and 0xffff
        // Tags sorted as the format asks (ASCII, so String order is byte order).
        val tables = java.util.TreeMap<String, ByteArray>()
        for (i in 0 until count) {
            val at = 12 + 16 * i
            val off = src.getInt(at + 8)
            tables[String(font, at, 4, Charsets.ISO_8859_1)] = font.copyOfRange(off, off + src.getInt(at + 12))
        }
        tables["name"] = names(tables.getValue("name"), family, style)
        // head's checkSumAdjustment is left out of every checksum, then set for the whole file.
        tables["head"] = tables.getValue("head").copyOf().also { java.util.Arrays.fill(it, 8, 12, 0) }

        val n = tables.size
        var selector = 0
        while (1 shl (selector + 1) <= n) selector++
        val range = 16 shl selector
        val header = ByteBuffer.allocate(12 + 16 * n)
        header.putInt(version).putShort(n.toShort()).putShort(range.toShort()).putShort(selector.toShort()).putShort((16 * n - range).toShort())
        val body = ByteArrayOutputStream()
        var headAt = 0
        for ((tag, data) in tables) {
            val at = 12 + 16 * n + body.size()
            if (tag == "head") headAt = at
            header.put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(checksum(data)).putInt(at).putInt(data.size)
            body.write(data)
            repeat((4 - data.size % 4) % 4) { body.write(0) }
        }
        val out = header.array() + body.toByteArray()
        ByteBuffer.wrap(out).putInt(headAt + 8, 0xB1B0AFBA.toInt() - checksum(out))
        return out
    }

    private fun names(old: ByteArray, family: String, style: String): ByteArray {
        val b = ByteBuffer.wrap(old)
        val count = b.getShort(2).toInt() and 0xffff
        val storage = b.getShort(4).toInt() and 0xffff
        val text = java.util.TreeMap<Int, String>()
        for (i in 0 until count) {
            val at = 6 + 12 * i
            fun u16(k: Int) = b.getShort(at + 2 * k).toInt() and 0xffff
            if (u16(0) == 3 && u16(1) == 1 && u16(2) == 0x409 && u16(3) in KEPT) {
                text[u16(3)] = String(old, storage + u16(5), u16(4), Charsets.UTF_16BE)
            }
        }
        val full = if (style == "Regular") family else "$family $style"
        text[1] = family
        text[2] = style
        text[3] = "ARMFlash: $full"
        text[4] = full
        text[6] = (if (style == "Regular") family else "$family-$style").replace(" ", "")
        // Records sorted by platform, encoding, language, then name: Mac Roman first, then Windows.
        val records = mutableListOf<IntArray>()
        val strings = ByteArrayOutputStream()
        for (windows in listOf(false, true)) for ((id, s) in text) {
            if (!windows && id !in MAC) continue
            val bytes = if (windows) s.toByteArray(Charsets.UTF_16BE) else s.toByteArray(Charsets.ISO_8859_1)
            records += if (windows) intArrayOf(3, 1, 0x409, id, bytes.size, strings.size()) else intArrayOf(1, 0, 0, id, bytes.size, strings.size())
            strings.write(bytes)
        }
        val out = ByteBuffer.allocate(6 + 12 * records.size + strings.size())
        out.putShort(0).putShort(records.size.toShort()).putShort((6 + 12 * records.size).toShort())
        records.forEach { r -> r.forEach { out.putShort(it.toShort()) } }
        out.put(strings.toByteArray())
        return out.array()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < data.size) {
            var word = 0
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < data.size) data[i + k].toInt() and 0xff else 0)
            sum += word
            i += 4
        }
        return sum
    }
}
