package com.armsx.flashpoint.win

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sound for Windows players: the other end of ARMFlash's ALSA plugin
 * (tools/winrt/alsa/pcm_android_aserver.c). Each stream Wine opens connects here and plays
 * through its own AudioTrack; a blocking write paces the stream. The protocol is in the
 * plugin's header comment.
 */
class AudioServer(val name: String) {
    private val server = LocalServerSocket(name)
    private val tracks = CopyOnWriteArrayList<AudioTrack>()
    @Volatile private var running = true
    @Volatile var volume = 1f
        set(v) { field = v; tracks.forEach { runCatching { it.setVolume(v) } } }

    init {
        Thread({
            while (running) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ serve(s) }, "win-audio-client").apply { isDaemon = true }.start()
            }
        }, "win-audio").apply { isDaemon = true }.start()
    }

    private fun serve(sock: LocalSocket) {
        val inp = DataInputStream(sock.inputStream.buffered(1 shl 16))
        val head = ByteArray(5)
        var track: AudioTrack? = null
        var buf = ByteArray(0)
        try {
            while (running) {
                inp.readFully(head)
                val len = ByteBuffer.wrap(head, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (head[0].toInt()) {
                    4 -> { // PREPARE
                        val b = ByteArray(len).also { inp.readFully(it) }
                        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                        val channels = b[0].toInt()
                        val encoding = when (b[1].toInt()) {
                            0 -> AudioFormat.ENCODING_PCM_8BIT
                            2 -> AudioFormat.ENCODING_PCM_32BIT
                            3 -> AudioFormat.ENCODING_PCM_FLOAT
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }
                        val rate = bb.getInt(2)
                        Log.i(WinRuntime.TAG, "audio stream: $channels ch, format ${b[1]}, $rate Hz")
                        track?.let { tracks.remove(it); it.release() }
                        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                        val min = AudioTrack.getMinBufferSize(rate, mask, encoding).coerceAtLeast(4096)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(mask).build())
                            .setBufferSizeInBytes(min * 2)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build().also { it.setVolume(volume); tracks.add(it) }
                    }
                    5 -> { // WRITE
                        if (buf.size < len) buf = ByteArray(len)
                        inp.readFully(buf, 0, len)
                        val t = track ?: continue
                        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                        t.write(buf, 0, len, AudioTrack.WRITE_BLOCKING)
                    }
                    1 -> track?.play()
                    2 -> track?.let { it.pause(); it.flush() }
                    3 -> track?.pause()
                    6 -> track?.stop()
                    0 -> break
                    else -> if (len > 0) inp.skipBytes(len)
                }
            }
        } catch (_: java.io.EOFException) {
        } catch (e: Exception) {
            Log.w(WinRuntime.TAG, "audio stream: ${e.message}")
        } finally {
            track?.let { tracks.remove(it); it.release() }
            runCatching { sock.close() }
        }
    }

    fun pause(paused: Boolean) = tracks.forEach { runCatching { if (paused) it.pause() else it.play() } }

    fun close() {
        running = false
        runCatching { server.close() }
        tracks.forEach { runCatching { it.release() } }
    }
}
