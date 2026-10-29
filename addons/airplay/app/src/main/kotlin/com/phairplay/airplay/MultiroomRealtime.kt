package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.phairplay.airplay.handshake.AlacDecoder
import com.phairplay.util.Logger
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.TreeMap

/**
 * AudioTrack output with the user delay trim (silence / dropped audio at (re)start and live deltas),
 * 4 ms fades against clicks. Same behaviour as the buffered-audio path in [MultiroomAudio], kept as a
 * separate class so that proven path stays untouched.
 */
internal class PcmOutput(private val sampleRate: Int, private val delayProvider: () -> Int) {
    private val track: AudioTrack
    @Volatile var startAdjust = true
    @Volatile private var dropBytes = 0L
    @Volatile private var fadeInNext = false
    private val bytesPerMs = sampleRate * 4 / 1000
    private val scheduler = PlayoutScheduler(sampleRate)
    private var sink: PlayoutSink? = null

    init {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4 / 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        sink = AudioTrackSink(track, sampleRate)
    }

    fun pause() { runCatching { track.pause() } }
    fun resume() { runCatching { track.play() } }

    /** New playback after rate 0 -> 1: legacy trim and PTP alignment both restart. */
    fun restartAlignment() { startAdjust = true; if (scheduler.isActive()) sink?.flush(); scheduler.requestReset() }

    fun flush() {
        runCatching { track.pause(); track.flush(); track.play() }
        startAdjust = true; dropBytes = 0
        scheduler.requestReset()
    }

    fun writeSilence(frames: Int) {
        val b = ByteArray(frames * 4)
        track.write(b, 0, b.size, AudioTrack.WRITE_BLOCKING)
        scheduler.noteWritten(frames)
    }

    fun release() { runCatching { track.stop() }; runCatching { track.release() } }

    fun write(pcm: ByteArray, rtp: Long) {
        val t = track
        if (scheduler.process(pcm, rtp, sink!!)) { startAdjust = false; dropBytes = 0; return }
        if (startAdjust) {
            startAdjust = false
            val d = delayProvider().coerceIn(-2000, 2000)
            if (d > 0) {
                val silence = ByteArray(d * bytesPerMs / 4 * 4)
                t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                scheduler.noteWritten(silence.size / 4)
                Logger.i("MR: realtime audio delay trim +${d}ms (silence ${silence.size}B)")
            } else if (d < 0) {
                dropBytes = (-d * bytesPerMs / 4 * 4).toLong()
                Logger.i("MR: realtime audio delay trim ${d}ms (dropping ${dropBytes}B)")
            }
        }
        var off = 0
        var len = pcm.size
        if (dropBytes > 0) {
            val skip = minOf(dropBytes, len.toLong()).toInt() / 4 * 4
            dropBytes -= skip; off = skip; len -= skip
            fadeInNext = true
        }
        if (len <= 0) return
        val out = if (off == 0 && len == pcm.size) pcm.copyOf() else pcm.copyOfRange(off, off + len)
        val fadeBytes = bytesPerMs * 4
        if (fadeInNext) { fade(out, 0, minOf(fadeBytes, out.size), true); fadeInNext = false }
        t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        scheduler.noteWritten(out.size / 4)
    }

    private fun fade(buf: ByteArray, from: Int, to: Int, fadeIn: Boolean) {
        val f = from / 4 * 4
        val n = (to - f) / 4
        if (n <= 0) return
        for (k in 0 until n) {
            val g = if (fadeIn) k.toFloat() / n else 1f - k.toFloat() / n
            for (ch in 0..1) {
                val p = f + k * 4 + ch * 2
                val v = ((buf[p].toInt() and 0xFF) or (buf[p + 1].toInt() shl 8)).toShort().toInt()
                val o = (v * g).toInt()
                buf[p] = o.toByte(); buf[p + 1] = (o shr 8).toByte()
            }
        }
    }
}

/**
 * AirPlay 2 realtime audio stream (SETUP stream type 96): encrypted RTP over UDP on [dataPort].
 * Datagram = RTP header (12 B) | ciphertext | tag (16 B) | nonce (8 B), ChaCha20-Poly1305 keyed by the stream
 * `shk` (same sealing as the buffered stream). Payload = one ALAC (ct=2) or AAC-LC (ct=4) frame.
 * No PTP clock: packets are re-ordered through a small jitter buffer and played as they arrive.
 */
internal class MultiroomRealtimeAudio(
    private val shk: ByteArray,
    private val ct: Int,
    private val sampleRate: Int,
    private val spf: Int,
    private val delayProvider: () -> Int = { MULTIROOM_AUDIO_DELAY_MS },
    private val onSampleRate: (Int) -> Unit = {},
) : AutoCloseable {
    private class Pkt(val seq: Long, val ts: Long, val payload: ByteArray)

    private val dataSock = DatagramSocket(0).apply { runCatching { receiveBufferSize = 1 shl 20 } }
    private val controlSock = DatagramSocket(0)
    val dataPort: Int = dataSock.localPort
    val controlPort: Int = controlSock.localPort

    @Volatile private var closed = false
    @Volatile private var playing = true
    @Volatile private var flushRequested = false
    private val lock = Object()
    private val buffer = TreeMap<Long, Pkt>()
    private var lastSeq16 = -1
    private var seqBase = 0L
    private var aadMode = -1      // -1 = not yet determined; 0 = bytes 4..12, 1 = whole header, 2 = none
    private var badBlocks = 0
    private var packets = 0L
    private var lostPackets = 0L
    private var output: PcmOutput? = null

    fun start() {
        Thread({ runControl() }, "mr-rt-ctrl").also { it.isDaemon = true }.start()
        Thread({ runReader() }, "mr-rt-reader").also { it.isDaemon = true }.start()
        Thread({ runPlayer() }, "mr-rt-player").also { it.isDaemon = true }.start()
        Logger.i("MR: realtime audio listening udp-data=$dataPort udp-control=$controlPort ct=$ct ${sampleRate}Hz spf=$spf")
    }

    fun setRate(rate: Float) {
        Logger.i("MR: realtime audio rate=$rate")
        if (rate > 0f) {
            if (!playing) { output?.restartAlignment(); output?.resume() }
            playing = true
        } else {
            playing = false
            output?.pause()
        }
    }

    fun flush() {
        Logger.i("MR: realtime audio flush (buffered=${synchronized(lock) { buffer.size }})")
        flushRequested = true
        synchronized(lock) { buffer.clear(); lock.notifyAll() }
    }

    private fun runControl() {
        try {
            val buf = ByteArray(2048)
            val dp = DatagramPacket(buf, buf.size)
            var n = 0
            while (!closed) {
                controlSock.receive(dp)
                if (n++ < 8) Logger.d("MR: realtime control udp ${dp.length}B head=${hex(buf, minOf(24, dp.length))}")
            }
        } catch (e: Exception) {
            if (!closed) Logger.d("MR: realtime control closed: ${e.message}")
        }
    }

    private fun runReader() {
        try {
            val buf = ByteArray(65535)
            val dp = DatagramPacket(buf, buf.size)
            while (!closed) {
                dp.length = buf.size
                dataSock.receive(dp)
                val len = dp.length
                if (len < 12 + 16 + 8 + 1) continue
                val pkt = buf.copyOf(len)
                val seq16 = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
                val ts = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                    ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
                val plain = decrypt(pkt)
                if (plain == null) {
                    if (badBlocks++ < 5) Logger.w("MR: realtime block seq=$seq16 failed authentication (len=$len head=${hex(pkt, 16)})")
                    continue
                }
                if (packets++ < 6) Logger.i("MR: realtime block seq=$seq16 ts=$ts payload=${plain.size}B")
                synchronized(lock) {
                    // Extend the 16-bit RTP sequence number to a monotonic 64-bit one.
                    if (lastSeq16 >= 0) {
                        val d = (seq16 - lastSeq16) and 0xFFFF
                        if (d < 0x8000 && seq16 < lastSeq16) seqBase += 0x10000
                        if (d >= 0x8000 && seq16 > lastSeq16) seqBase -= 0x10000
                    }
                    lastSeq16 = seq16
                    val useq = seqBase + seq16
                    buffer[useq] = Pkt(useq, ts, plain)
                    lock.notifyAll()
                }
            }
        } catch (e: Exception) {
            if (!closed) Logger.i("MR: realtime data socket ended: ${e.message}")
        }
    }

    /** Tries the documented AAD (RTP timestamp+ssrc) first; falls back to other variants once and remembers which works. */
    private fun decrypt(pkt: ByteArray): ByteArray? {
        val order = if (aadMode >= 0) intArrayOf(aadMode) else intArrayOf(0, 1, 2)
        for (mode in order) {
            val out = openWith(pkt, mode)
            if (out != null) {
                if (aadMode < 0) { aadMode = mode; Logger.i("MR: realtime AEAD associated-data mode=$mode") }
                return out
            }
        }
        return null
    }

    private fun openWith(pkt: ByteArray, mode: Int): ByteArray? = try {
        val nonce = ByteArray(12)
        System.arraycopy(pkt, pkt.size - 8, nonce, 4, 8)
        val aad = when (mode) { 0 -> pkt.copyOfRange(4, 12); 1 -> pkt.copyOfRange(0, 12); else -> ByteArray(0) }
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(shk), 128, nonce, aad))
        val clen = pkt.size - 8 - 12
        val out = ByteArray(c.getOutputSize(clen))
        val n = c.processBytes(pkt, 12, clen, out, 0)
        out.copyOf(n + c.doFinal(out, n))
    } catch (e: Exception) { null }

    private fun runPlayer() {
        var alac: AlacDecoder? = null
        var aac: AacDecoder? = null
        try {
            onSampleRate(sampleRate)
            when (ct) {
                2 -> alac = AlacDecoder(sampleRate, 2, spf)
                4 -> aac = AacDecoder(sampleRate, 2)
                else -> error("unsupported realtime ct=$ct")
            }
            val out = PcmOutput(sampleRate, delayProvider).also { output = it }
            Logger.i("MR: realtime audio output started ${if (ct == 2) "ALAC" else "AAC-LC"} ${sampleRate}Hz stereo spf=$spf")
            val frameMs = spf * 1000L / sampleRate
            val prebuffer = maxOf(4, (100 / maxOf(1, frameMs)).toInt())          // ~100 ms
            val maxWaitMs = 60L
            var expected = -1L
            var gapWaitedMs = 0L
            while (!closed) {
                var pkt: Pkt? = null
                var silenceFrames = 0
                synchronized(lock) {
                    if (flushRequested) { flushRequested = false; expected = -1; out.flush() }
                    if (expected < 0) {
                        // (Re)start: wait for ~100 ms of audio before playing.
                        if (buffer.size < prebuffer) { lock.wait(50); pkt = null; return@synchronized }
                    }
                    if (buffer.isEmpty()) { lock.wait(20); pkt = null; return@synchronized }
                    val first = buffer.firstEntry().value
                    if (expected < 0) expected = first.seq
                    when {
                        first.seq < expected -> { buffer.remove(first.seq); pkt = null }
                        first.seq == expected -> { buffer.remove(first.seq); expected++; gapWaitedMs = 0; pkt = first }
                        else -> {
                            if (buffer.size >= 14 || gapWaitedMs >= maxWaitMs) {
                                val missing = (first.seq - expected).coerceIn(1, 50).toInt()
                                lostPackets += missing
                                if (lostPackets <= 10 || lostPackets % 200 == 0L) Logger.w("MR: realtime lost $missing packet(s) (total=$lostPackets)")
                                silenceFrames = missing * spf
                                expected = first.seq
                                gapWaitedMs = 0
                            } else { lock.wait(5); gapWaitedMs += 5 }
                            pkt = null
                        }
                    }
                }
                if (silenceFrames > 0 && playing) out.writeSilence(silenceFrames)
                val cur: Pkt = pkt ?: continue
                while (!playing && !closed) Thread.sleep(40)
                if (closed) break
                val pcm = try {
                    val a = alac
                    if (a != null) a.decode(cur.payload) else {
                        var acc: ByteArray? = null
                        aac!!.decode(cur.payload) { p -> acc = p }
                        acc
                    }
                } catch (e: Exception) { Logger.w("MR: realtime decode failed: ${e.message}"); null }
                if (pcm != null && pcm.isNotEmpty()) out.write(pcm, cur.ts)
            }
        } catch (e: Exception) {
            if (!closed) Logger.e("MR: realtime audio player stopped", e)
        } finally {
            runCatching { alac?.close() }
            runCatching { aac?.release() }
            output?.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        playing = false
        runCatching { dataSock.close() }
        runCatching { controlSock.close() }
        synchronized(lock) { buffer.clear(); lock.notifyAll() }
    }

    private companion object {
        fun hex(b: ByteArray, len: Int) = (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }
    }
}
