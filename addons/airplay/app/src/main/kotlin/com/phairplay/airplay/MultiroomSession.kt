package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.phairplay.airplay.handshake.HapInputStream
import com.phairplay.airplay.handshake.HapOutputStream
import com.phairplay.airplay.handshake.HapSecureSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AirPlay 2 multi-room (PTP-mode) session on one authenticated RTSP connection: event channel, and a
 * buffered-audio (stream type 103) receiver. There is no PTP clock here (the projector has no root,
 * so UDP 319/320 are unavailable): audio is started when SETRATEANCHORTIME(rate=1) arrives, unsynchronised.
 */
/** Fixed multi-room output offset in ms (negative = play earlier). Empirical: tuned by ear on clicks, 2026-10-02; no PTP sync. */
internal const val MULTIROOM_AUDIO_DELAY_MS = -600

internal class MultiroomSession(
    private val peer: java.net.InetAddress,
    private val pairing: HapSecureSession,
    private val delayProvider: () -> Int = { MULTIROOM_AUDIO_DELAY_MS },
    private val onSampleRate: (Int) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wireVariant = ptpWireVariant()
    private val events = if (ptpSharedPorts(wireVariant)) runCatching { ServerSocket(3319) }.getOrElse { ServerSocket(0) } else ServerSocket(0)
    @Volatile private var closed = false
    @Volatile private var audio: MultiroomAudio? = null
    @Volatile private var realtime: MultiroomRealtimeAudio? = null
    val eventPort: Int = events.localPort
    private val ptpProbe = PtpProbe(if (ptpSlaveMode(wireVariant)) peer.hostAddress else null)
    @Volatile private var timingPeerInfoForCommand: Map<String, Any?>? = null
    @Volatile private var ntpClock: NtpClockAdapter? = null

    fun start() {
        ptpProbe.start()
        scope.launch { runEvents() }
    }

    /** Phase 1 (control) SETUP response — same shape shairport-sync returns for timingProtocol=PTP. */
    fun controlSetupResponse(req: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        if (req["timingProtocol"] == "NTP") return ntpSetupResponse(req)
        val variant = ptpWireVariant()
        val addresses = ownAddresses().let { all -> if (variant == "b5") all.filter { !it.contains(':') } else all }
        val peerInfo = mutableMapOf<String, Any?>(
            "Addresses" to addresses,
            "ID" to (addresses.firstOrNull { !it.contains(':') } ?: addresses.firstOrNull() ?: ""),
        )
        if (ptpFullPeerInfo(variant)) {
            peerInfo["ClockID"] = PTP_CLOCK_ID
            peerInfo["DeviceType"] = 1L
            peerInfo["SupportsClockPortMatchingOverride"] = true
            val ev = ptpProbe.eventPort.toLong()
            val senders = (listOfNotNull(req["timingPeerInfo"] as? Map<*, *>) + ((req["timingPeerList"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()))
            val senderClockIds = senders.mapNotNull { (it["ClockID"] as? Number)?.toLong() }.distinct().map { it.toULong().toString() }
            val senderUuids = senders.mapNotNull { it["ID"] as? String }.distinct()
            val ownClock = PTP_CLOCK_ID.toULong().toString()
            when (variant) {
                "a4" -> peerInfo["ClockPorts"] = mapOf(ownClock to ev)
                "b1", "b5", "b6", "b7" -> peerInfo["ClockPorts"] = (senderClockIds + ownClock).distinct().associateWith { ev }
                "b2" -> peerInfo["ClockPorts"] = (senderUuids + (peerInfo["ID"] as String)).distinct().associateWith { ev }
                "b3" -> peerInfo["ClockPorts"] = listOf(ev, ptpProbe.generalPort.toLong())
                "b4", "b8" -> peerInfo["ClockPorts"] = (senderClockIds + senderUuids + ownClock + (peerInfo["ID"] as String)).distinct().associateWith { ev }
            }
        }
        timingPeerInfoForCommand = peerInfo.toMap()
        val resp = mutableMapOf<String, Any?>("eventPort" to eventPort.toLong(), "timingPort" to 0L, "timingPeerInfo" to peerInfo)
        if (variant == "v1" || variant == "v3") {
            // ClockPorts {ClockID(decimal string): port}; keys of a plist dict are always strings
            val ids = (listOfNotNull(req["timingPeerInfo"] as? Map<*, *>) + ((req["timingPeerList"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()))
                .mapNotNull { (it["ClockID"] as? Number)?.toLong() }.distinct()
            peerInfo["ClockPorts"] = ids.associate { it.toULong().toString() to ptpProbe.eventPort.toLong() }
        }
        if (variant == "v2" || variant == "v3") resp["timingPort"] = ptpProbe.eventPort.toLong()
        if (ptpSharedPorts(variant)) resp["timingPort"] = ptpProbe.generalPort.toLong()
        Logger.i("MR: PTP wire variant=$variant eventPort(ptp)=${ptpProbe.eventPort} generalPort=${ptpProbe.generalPort} peerInfo=$peerInfo timingPort=${resp["timingPort"]}")
        return resp
    }

    /** NTP timing (receiver does not advertise bit 41): we poll the sender's timingPort; reply like UxPlay/airplay2-receiver. */
    private fun ntpSetupResponse(req: Map<String, Any?>): Map<String, Any?> {
        val senderPort = (req["timingPort"] as? Number)?.toInt() ?: 0
        Logger.i("MR: NTP SETUP #1 senderTimingPort=$senderPort keys=${req.keys}")
        ntpClock?.close()
        val c = NtpClockAdapter(peer, senderPort).also { it.start() }
        ntpClock = c
        MultiroomClockSource.clock = c
        return mapOf("eventPort" to eventPort.toLong(), "timingPort" to c.localPort.toLong())
    }

    fun setupAudioStream(stream: Map<*, *>): Map<String, Any?> {
        val shk = stream["shk"] as? ByteArray ?: error("buffered audio SETUP without shk")
        require(shk.size == 32) { "unexpected shk length ${shk.size}" }
        Logger.i("MR: audio stream ct=${stream["ct"]} audioFormat=${stream["audioFormat"]} sr=${stream["sr"]} spf=${stream["spf"]} " +
            "latencyMin=${stream["latencyMin"]} latencyMax=${stream["latencyMax"]} controlPort(sender)=${stream["controlPort"]}")
        audio?.close()
        val a = MultiroomAudio(shk, delayProvider, onSampleRate).also { it.start() }
        audio = a
        return mapOf(
            "type" to 103L,
            "dataPort" to a.dataPort.toLong(),
            "controlPort" to a.controlPort.toLong(),
            "audioBufferSize" to 4194304L,
        )
    }

    /** Realtime (type 96) stream: encrypted RTP over UDP, ALAC (ct=2) or AAC-LC (ct=4). */
    fun setupRealtimeStream(stream: Map<*, *>): Map<String, Any?> {
        val shk = stream["shk"] as? ByteArray ?: error("realtime audio SETUP without shk")
        require(shk.size == 32) { "unexpected shk length ${shk.size}" }
        val ct = (stream["ct"] as? Number)?.toInt() ?: error("realtime audio SETUP without ct")
        require(ct == 2 || ct == 4) { "unsupported realtime codec ct=$ct" }
        val spf = (stream["spf"] as? Number)?.toInt() ?: if (ct == 2) 352 else 1024
        val sr = (stream["sr"] as? Number)?.toInt() ?: 44100
        require(sr == 44100 || sr == 48000) { "unsupported realtime sample rate $sr" }
        Logger.i("MR: realtime stream ct=$ct audioFormat=${stream["audioFormat"]} sr=$sr spf=$spf " +
            "latencyMin=${stream["latencyMin"]} latencyMax=${stream["latencyMax"]} controlPort(sender)=${stream["controlPort"]} " +
            "streamConnectionID=${stream["streamConnectionID"]} keys=${stream.keys}")
        realtime?.close()
        val r = MultiroomRealtimeAudio(shk, ct, sr, spf, delayProvider, onSampleRate).also { it.start() }
        realtime = r
        return mapOf(
            "type" to 96L,
            "dataPort" to r.dataPort.toLong(),
            "controlPort" to r.controlPort.toLong(),
        )
    }

    fun hasRealtime(): Boolean = realtime != null

    fun setRate(rate: Float, rtpTime: Long?) { audio?.setRate(rate, rtpTime); realtime?.setRate(rate) }

    fun flush(fromSeq: Long?, untilSeq: Long?) { audio?.flush(fromSeq, untilSeq) }

    /** RTSP FLUSH (realtime streams) or a full FLUSHBUFFERED. */
    fun flushRealtime() { realtime?.flush() }


    fun stopAudio() { audio?.close(); audio = null }

    fun stopRealtime() { realtime?.close(); realtime = null }

    private fun runEvents() {
        try {
            val socket = events.accept()
            socket.tcpNoDelay = true
            Logger.i("MR: event channel connected from ${socket.inetAddress.hostAddress}")
            val input = HapInputStream(socket.getInputStream(), pairing.key("Events-Salt", "Events-Read-Encryption-Key"))
            val output = HapOutputStream(socket.getOutputStream(), pairing.key("Events-Salt", "Events-Write-Encryption-Key"))
            val reader = RtspRequestReader(65536, 65536, 65536)
            val variant = ptpWireVariant()
            val info = timingPeerInfoForCommand
            if (ptpSendsAsyncCommand(variant) && info != null) {
                val body = PlistCodec.encode(mapOf("type" to "updateTimingPeerInfo", "value" to info))
                val head = "POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\n" +
                    "User-Agent: AirPlay/PhairPlay\r\nContent-Length: ${body.size}\r\n\r\n"
                output.write(head.toByteArray() + body)
                output.flush()
                Logger.i("MR: PTP wire variant=$variant sent POST /command updateTimingPeerInfo value=$info")
            }
            while (!closed) {
                val msg = reader.read(input) ?: break
                Logger.i("MR: event ${msg.method} ${msg.uri} (${msg.bodyBytes.size}B)")
                if (msg.method.startsWith("RTSP/")) Logger.i("MR: PTP wire event-channel reply: ${msg.method} ${msg.uri} cseq=${msg.headers["CSeq"]} body=${msg.bodyBytes.size}B")
                if (!msg.method.startsWith("HTTP/") && !msg.method.startsWith("RTSP/")) {
                    val cseq = msg.headers["CSeq"] ?: "0"
                    output.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
        } catch (e: Exception) {
            if (!closed) Logger.w("MR: event channel ended: ${e.message}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { events.close() }
        ptpProbe.close()
        MultiroomPtp.clearAnchor()
        ntpClock?.close()
        audio?.close()
        realtime?.close()
        scope.cancel()
    }

    private fun ownAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { a ->
                when (a) {
                    is Inet4Address -> a.hostAddress
                    is Inet6Address -> a.hostAddress?.substringBefore('%')
                    else -> null
                }
            }
    }.getOrDefault(emptyList())
}

/** TCP buffered-audio receiver (type 103): [len u16][seq u32][ts u32][ssrc u32][ciphertext][tag 16][nonce 8]. */
internal class MultiroomAudio(
    private val shk: ByteArray,
    private val delayProvider: () -> Int = { MULTIROOM_AUDIO_DELAY_MS },
    private val onSampleRate: (Int) -> Unit = {},
) : AutoCloseable {
    private class Pkt(val seq: Int, val ts: Long, val ssrc: Long, val payload: ByteArray)

    private val dataServer = ServerSocket(0)
    private val control = DatagramSocket(0)
    val dataPort: Int = dataServer.localPort
    val controlPort: Int = control.localPort

    @Volatile private var closed = false
    @Volatile private var playing = false
    @Volatile private var anchorRtp = -1L
    @Volatile private var dropUntilSeq = -1
    @Volatile private var startAdjust = true      // apply the user delay trim at the next first PCM
    @Volatile private var dropBytes = 0L          // negative delay: PCM bytes still to discard
    @Volatile private var sampleRate = 44100
    @Volatile private var startedAtMs = 0L
    @Volatile private var latencyLogged = false
    @Volatile private var fadeInNext = false
    private val queue = LinkedBlockingQueue<Pkt>(4096)
    private var track: AudioTrack? = null
    private var scheduler: PlayoutScheduler? = null   // PTP playout alignment; inactive (legacy delay trim) while clock not synced
    private var sink: PlayoutSink? = null
    private var decoder: AacDecoder? = null
    private var decoderSsrc = 0L
    private var packets = 0L
    private var lastTs = -1L

    fun start() {
        Thread({ runControl() }, "mr-ctrl").also { it.isDaemon = true }.start()
        Thread({ runReader() }, "mr-reader").also { it.isDaemon = true }.start()
        Thread({ runPlayer() }, "mr-player").also { it.isDaemon = true }.start()
        Logger.i("MR: buffered audio listening tcp=$dataPort udp-control=$controlPort")
    }

    fun setRate(rate: Float, rtpTime: Long?) {
        Logger.i("MR: audio rate=$rate anchorRtp=$rtpTime (queued=${queue.size})")
        if (rate > 0f) {
            if (rtpTime != null) anchorRtp = rtpTime
            if (!playing) { startAdjust = true; if (scheduler?.isActive() == true) sink?.flush(); scheduler?.requestReset(); latencyLogged = false; startedAtMs = System.currentTimeMillis() }
            playing = true
            track?.play()
        } else {
            playing = false
            track?.pause()
        }
    }

    fun flush(fromSeq: Long?, untilSeq: Long?) {
        Logger.i("MR: audio flush from=$fromSeq until=$untilSeq (queued=${queue.size})")
        if (fromSeq == null) {
            playing = false
            track?.pause()
            queue.clear()
            track?.flush()
            startAdjust = true; dropBytes = 0
            scheduler?.requestReset()
            anchorRtp = -1
            if (untilSeq != null) dropUntilSeq = (untilSeq and 0x7FFFFF).toInt()
        } else {
            queue.removeIf { p ->
                val s = p.seq.toLong()
                s >= (fromSeq and 0x7FFFFF) && (untilSeq == null || s < (untilSeq and 0x7FFFFF))
            }
        }
    }

    private fun runControl() {
        try {
            val buf = ByteArray(2048)
            val dp = DatagramPacket(buf, buf.size)
            var n = 0
            while (!closed) {
                control.receive(dp)
                if (n++ < 12) Logger.d("MR: audio control udp ${dp.length}B head=${hex(buf, minOf(24, dp.length))}")
            }
        } catch (e: Exception) {
            if (!closed) Logger.d("MR: audio control closed: ${e.message}")
        }
    }

    private fun runReader() {
        try {
            val socket: Socket = dataServer.accept()
            socket.tcpNoDelay = true
            Logger.i("MR: audio data connection from ${socket.inetAddress.hostAddress}")
            val input = DataInputStream(socket.getInputStream().buffered(65536))
            var bad = 0
            while (!closed) {
                val len = input.readUnsignedShort()
                if (len < 2 + 12 + 16 + 8) { Logger.w("MR: short audio block len=$len"); continue }
                val body = ByteArray(len - 2)
                input.readFully(body)
                val bb = ByteBuffer.wrap(body)
                val seq = bb.getInt(0) and 0x7FFFFF
                val ts = bb.getInt(4).toLong() and 0xFFFFFFFFL
                val ssrc = bb.getInt(8).toLong() and 0xFFFFFFFFL
                val plain = decrypt(body)
                if (plain == null) {
                    if (bad++ < 5) Logger.w("MR: audio block $seq failed authentication")
                    continue
                }
                if (packets++ < 6) Logger.i("MR: audio block seq=$seq ts=$ts ssrc=0x${ssrc.toString(16)} payload=${plain.size}B")
                queue.put(Pkt(seq, ts, ssrc, plain))
            }
        } catch (e: Exception) {
            if (!closed) Logger.i("MR: audio data connection ended: ${e.message}")
        }
    }

    private fun decrypt(body: ByteArray): ByteArray? = try {
        val nonce = ByteArray(12)
        System.arraycopy(body, body.size - 8, nonce, 4, 8)
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(shk), 128, nonce, body.copyOfRange(4, 12)))
        val clen = body.size - 8 - 12
        val out = ByteArray(c.getOutputSize(clen))
        val n = c.processBytes(body, 12, clen, out, 0)
        out.copyOf(n + c.doFinal(out, n))
    } catch (e: Exception) { null }

    private fun runPlayer() {
        try {
            while (!closed) {
                val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (dropUntilSeq >= 0) {
                    if (p.seq < dropUntilSeq) continue else dropUntilSeq = -1
                }
                while (!playing && !closed) Thread.sleep(40)
                if (closed) break
                if (anchorRtp >= 0 && ((p.ts - anchorRtp) and 0xFFFFFFFFL) > 0x7FFFFFFFL) continue // before the anchor
                val dec = decoderFor(p.ssrc) ?: continue
                var rtp = p.ts
                dec.decode(p.payload) { pcm -> writePcm(pcm, rtp); rtp = (rtp + pcm.size / 4) and 0xFFFFFFFFL }
                logLatencyOnce()
            }
        } catch (e: Exception) {
            if (!closed) Logger.e("MR: audio player stopped", e)
        } finally {
            runCatching { decoder?.release() }
            runCatching { track?.stop(); track?.release() }
        }
    }

    /** Writes decoded PCM, applying the user delay trim once per (re)start: silence (+) or dropped audio (−). */
    private fun writePcm(pcm: ByteArray, rtp: Long) {
        val t = track ?: return
        val sch = scheduler
        val sk = sink
        if (sch != null && sk != null && sch.process(pcm, rtp, sk)) { startAdjust = false; dropBytes = 0; return }
        val bytesPerMs = sampleRate * 4 / 1000
        if (startAdjust) {
            startAdjust = false
            val d = delayProvider().coerceIn(-2000, 2000)
            if (d > 0) {
                val silence = ByteArray(d * bytesPerMs / 4 * 4)
                t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                sch?.noteWritten(silence.size / 4)
                Logger.i("MR: audio delay trim +${d}ms (silence ${silence.size}B)")
            } else if (d < 0) {
                dropBytes = (-d * bytesPerMs / 4 * 4).toLong()
                Logger.i("MR: audio delay trim ${d}ms (dropping ${dropBytes}B)")
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
        val fadeBytes = sampleRate * 4 / 1000 * 4        // ~4 ms of stereo 16-bit
        if (fadeInNext) { fade(out, 0, minOf(fadeBytes, out.size), true); fadeInNext = false }
        t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        sch?.noteWritten(out.size / 4)
    }

    /** Linear 16-bit PCM fade over [from, to) (stereo frames of 4 bytes): in = 0→1, out = 1→0. */
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

    /** Logs how much audio sits ahead of the speaker (AudioTrack buffer fill) a few seconds after start. */
    private fun logLatencyOnce() {
        if (latencyLogged || System.currentTimeMillis() - startedAtMs < 3000) return
        latencyLogged = true
        val t = track ?: return
        val ts = android.media.AudioTimestamp()
        if (t.getTimestamp(ts)) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            Logger.i("MR: audio output pipeline latency ~${t.latency_ms()}ms (head=$head presented=${ts.framePosition})")
        } else Logger.i("MR: audio AudioTrack.getTimestamp unavailable")
    }

    private fun AudioTrack.latency_ms(): Long {
        // buffer size in frames (we write blocking, so it is normally full) / sample rate
        return bufferSizeInFrames * 1000L / sampleRate
    }

    private fun decoderFor(ssrc: Long): AacDecoder? {
        if (ssrc == decoderSsrc && decoder != null) return decoder
        val rate = when (ssrc shr 24) {
            0x16L -> 44100
            0x17L -> 48000
            else -> { Logger.w("MR: unsupported audio SSRC 0x${ssrc.toString(16)}"); return null }
        }
        runCatching { decoder?.release() }
        runCatching { track?.stop(); track?.release() }
        decoder = AacDecoder(rate, 2)
        decoderSsrc = ssrc
        sampleRate = rate
        onSampleRate(rate)
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 8))   // ~125 ms (was ~500 ms) of audio ahead of the speaker
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        scheduler = PlayoutScheduler(rate)
        sink = AudioTrackSink(track!!, rate)
        Logger.i("MR: audio output started AAC-LC ${rate}Hz stereo")
        return decoder
    }

    override fun close() {
        if (closed) return
        closed = true
        playing = false
        runCatching { dataServer.close() }
        runCatching { control.close() }
        queue.clear()
    }

    private companion object {
        fun hex(b: ByteArray, len: Int) = (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }
    }
}

/** Raw AAC-LC frame decoder (MediaCodec, no ADTS). */
internal class AacDecoder(sampleRate: Int, channels: Int) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType("audio/mp4a-latm")
    private var ptsUs = 0L
    private val frameUs = 1024L * 1_000_000L / sampleRate

    init {
        val idx = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000).indexOf(sampleRate)
        require(idx >= 0)
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(((2 shl 3) or (idx shr 1)).toByte(), (((idx and 1) shl 7) or (channels shl 3)).toByte())))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun decode(frame: ByteArray, onPcm: (ByteArray) -> Unit) {
        val inIdx = codec.dequeueInputBuffer(50_000)
        if (inIdx >= 0) {
            codec.getInputBuffer(inIdx)!!.apply { clear(); put(frame) }
            codec.queueInputBuffer(inIdx, 0, frame.size, ptsUs, 0)
            ptsUs += frameUs
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                val buf = codec.getOutputBuffer(out)!!
                if (info.size > 0) {
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset); buf.get(pcm)
                    onPcm(pcm)
                }
                codec.releaseOutputBuffer(out, false)
            } else break
        }
    }

    fun release() { runCatching { codec.stop() }; runCatching { codec.release() } }
}
