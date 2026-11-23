package com.armsx.flashpoint.win

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough of the X11 protocol to drive a game on ARMFlash's virtual screen: find the game's
 * window, give it focus, and inject the player's input through XTEST. Written from the X11 and
 * XTEST protocol specifications; one connection, used from one thread at a time.
 */
class XClient private constructor(private val sock: LocalSocket) {
    private val out: OutputStream = sock.outputStream
    private val inp = DataInputStream(sock.inputStream.buffered())
    private var seq = 0
    var root = 0; private set
    var screenW = 0; private set
    var screenH = 0; private set
    private var minKey = 8
    private var xtest = 0
    private val keysymToCode = HashMap<Int, Int>()

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun send(b: ByteBuffer) { out.write(b.array()); out.flush(); seq = (seq + 1) and 0xffff }

    private fun readBytes(n: Int): ByteBuffer {
        val a = ByteArray(n); inp.readFully(a); return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** The reply to the request just sent (events are skipped; an X error gives null). */
    private fun reply(): ByteBuffer? {
        while (true) {
            val head = readBytes(32)
            when (head.get(0).toInt()) {
                0 -> { if ((head.getShort(2).toInt() and 0xffff) == seq) return null }
                1 -> {
                    val extra = head.getInt(4)
                    val body = if (extra > 0) readBytes(extra * 4).array() else ByteArray(0)
                    if ((head.getShort(2).toInt() and 0xffff) != seq) continue
                    return ByteBuffer.wrap(head.array() + body).order(ByteOrder.LITTLE_ENDIAN)
                }
                else -> continue
            }
        }
    }

    private fun handshake() {
        out.write(le(12).put('l'.code.toByte()).put(0).putShort(11).putShort(0).putShort(0).putShort(0).putShort(0).array())
        out.flush()
        val head = readBytes(8)
        val more = readBytes((head.getShort(6).toInt() and 0xffff) * 4)
        if (head.get(0).toInt() != 1) throw IOException("X server refused the connection")
        val vendorLen = more.getShort(16).toInt() and 0xffff
        val formats = more.get(21).toInt() and 0xff
        minKey = more.get(26).toInt() and 0xff
        val maxKey = more.get(27).toInt() and 0xff
        val screen = 32 + ((vendorLen + 3) and 3.inv()) + formats * 8
        root = more.getInt(screen)
        screenW = more.getShort(screen + 20).toInt() and 0xffff
        screenH = more.getShort(screen + 22).toInt() and 0xffff
        xtest = queryExtension("XTEST")
        loadKeymap(minKey, maxKey - minKey + 1)
    }

    private fun queryExtension(name: String): Int {
        val n = name.toByteArray()
        val pad = (n.size + 3) and 3.inv()
        val b = le(8 + pad).put(98).put(0).putShort(((8 + pad) / 4).toShort()).putShort(n.size.toShort()).putShort(0)
        b.put(n)
        send(b)
        val r = reply() ?: return 0
        return if (r.get(8).toInt() != 0) r.get(9).toInt() and 0xff else 0
    }

    private fun loadKeymap(first: Int, count: Int) {
        send(le(8).put(101).put(0).putShort(2).put(first.toByte()).put(count.toByte()).putShort(0))
        val r = reply() ?: return
        val per = r.get(1).toInt() and 0xff
        for (i in 0 until count) for (j in 0 until per) {
            val sym = r.getInt(32 + (i * per + j) * 4)
            if (sym != 0 && sym !in keysymToCode) keysymToCode[sym] = first + i
        }
    }

    fun keycode(keysym: Int): Int? = keysymToCode[keysym]

    /** Mapped top-level windows, largest first. */
    fun windows(): List<Pair<Int, Rect>> = children(root)

    /** Mapped child windows of [parent] (geometry relative to it), largest first. */
    fun children(parent: Int): List<Pair<Int, Rect>> {
        send(le(8).put(15).put(0).putShort(2).putInt(parent))
        val r = reply() ?: return emptyList()
        val n = r.getShort(16).toInt() and 0xffff
        val kids = (0 until n).map { r.getInt(32 + it * 4) }
        return kids.mapNotNull { w ->
            send(le(8).put(3).put(0).putShort(2).putInt(w))
            val a = reply() ?: return@mapNotNull null
            if (a.get(26).toInt() != 2) return@mapNotNull null // IsViewable
            send(le(8).put(14).put(0).putShort(2).putInt(w))
            val g = reply() ?: return@mapNotNull null
            val rect = Rect(g.getShort(12).toInt(), g.getShort(14).toInt(), g.getShort(16).toInt() and 0xffff, g.getShort(18).toInt() and 0xffff)
            if (rect.w < 16 || rect.h < 16) null else w to rect
        }.sortedByDescending { it.second.w * it.second.h }
    }

    /** A window's WM_CLASS ("instance class"): Wine names its windows after the Windows program. */
    fun wmClass(window: Int): String {
        // GetProperty: window, WM_CLASS (67), type STRING (31), from 0, up to 64 words.
        send(le(24).put(20).put(0).putShort(6).putInt(window).putInt(67).putInt(31).putInt(0).putInt(64))
        val r = reply() ?: return ""
        val n = r.getInt(16)
        if (n <= 0 || r.capacity() < 32 + n) return ""
        val b = ByteArray(n); r.position(32); r.get(b)
        return String(b, Charsets.ISO_8859_1).replace('\u0000', ' ').trim()
    }

    fun focus(window: Int) {
        // SetInputFocus, revert-to Parent, CurrentTime.
        send(le(12).put(42).put(2).putShort(3).putInt(window).putInt(0))
    }

    private fun fake(type: Int, detail: Int, x: Int = 0, y: Int = 0) {
        if (xtest == 0) return
        val b = le(36).put(xtest.toByte()).put(2).putShort(9)
            .put(type.toByte()).put(detail.toByte()).putShort(0).putInt(0).putInt(if (type == 6) root else 0)
        b.position(24); b.putShort(x.toShort()).putShort(y.toShort())
        send(b)
    }

    fun key(keycode: Int, down: Boolean) = fake(if (down) 2 else 3, keycode)
    fun button(button: Int, down: Boolean) = fake(if (down) 4 else 5, button)
    fun motion(x: Int, y: Int) = fake(6, 0, x, y)

    fun close() = runCatching { sock.close() }

    companion object {
        fun connect(socketPath: String): XClient {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            return XClient(s).also { it.handshake() }
        }
    }
}
