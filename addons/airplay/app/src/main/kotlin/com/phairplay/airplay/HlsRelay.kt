package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reversed (PTTH) HTTP channel: after `POST /reverse` (101 Switching Protocols) the sender's TCP connection is
 * used in the opposite direction — WE send HTTP requests (`POST /event`) and the sender answers.
 */
internal class ReverseChannel(private val socket: Socket, private val out: java.io.OutputStream = socket.getOutputStream()) {

    /** Sends an XML-plist `POST /event` to the sender. Returns false if the channel is dead. */
    @Synchronized
    fun sendEvent(sessionId: String, xmlPlist: ByteArray): Boolean = try {
        val head = "POST /event HTTP/1.1\r\n" +
            "X-Apple-Session-ID: $sessionId\r\n" +
            "Content-Type: text/x-apple-plist+xml\r\n" +
            "Content-Length: ${xmlPlist.size}\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII) + xmlPlist)
        out.flush()
        true
    } catch (e: Exception) {
        Logger.w("Reverse channel send failed: ${e.message}")
        false
    }

    fun close() = runCatching { socket.close() }
}

/**
 * HlsRelay — plays AirPlay "HLS relay" video (e.g. the YouTube app), where the sender hands us a fake location
 * (`mlhls://localhost/.../master.m3u8`) and expects US to fetch the playlists THROUGH THE SENDER ("FCUP"
 * requests on the reverse channel; the sender replies with `POST /action`). The media segments themselves are
 * normal https URLs on the CDN, fetched directly by the player.
 *
 * We collect the master playlist and every media playlist, rewrite the sender-side URI prefix to a loopback HTTP
 * server we run, and finally give the player `http://127.0.0.1:<port>/master.m3u8`. Protocol reference: UxPlay's
 * `http_handlers.h` / `airplay_video.c` (read for interoperability; no code is copied).
 */
internal class HlsRelay(
    private val sendFcup: (url: String, requestId: Int) -> Boolean,
    private val onReady: (localMasterUrl: String, startSeconds: Double) -> Unit,
) {
    private val lock = Any()
    private var server: ServerSocket? = null
    private var uriPrefix = ""
    private var localPrefix = ""
    private var startSeconds = 0.0
    private var master: String? = null
    private val mediaUris = ArrayList<String>()
    private val mediaPlaylists = HashMap<String, String>()   // path (relative to prefix) -> playlist text
    private var nextUri = 0
    private val requestIds = AtomicInteger(0)

    /** Starts a relay session for [location]; false if it is not an HLS master location. */
    fun start(location: String, startSeconds: Double): Boolean {
        val marker = location.indexOf("/master.m3u8")
        if (marker < 0) return false
        synchronized(lock) {
            stopLocked()
            this.startSeconds = startSeconds
            uriPrefix = location.substring(0, marker)
            master = null
            mediaUris.clear(); mediaPlaylists.clear(); nextUri = 0
            val ss = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
            server = ss
            localPrefix = "http://127.0.0.1:${ss.localPort}"
            Thread({ serve(ss) }, "HlsRelay-http").apply { isDaemon = true }.start()
            Logger.i("HLS relay: location=$location local=$localPrefix")
            return sendFcup(location, requestIds.incrementAndGet())
        }
    }

    /** Handles one `unhandledURLResponse` from the sender: the playlist [data] fetched for [url]. */
    fun onFcupResponse(url: String, data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        if (!text.startsWith("#EXTM3U")) {
            Logger.w("HLS relay: response for $url is not an m3u8 playlist")
            return
        }
        var readyUrl: String? = null
        var nextRequest: String? = null
        synchronized(lock) {
            if (server == null) return
            if (url.contains("/master.m3u8")) {
                mediaUris.clear(); mediaUris.addAll(mediaUriList(text))
                master = text.replace(uriPrefix, localPrefix)
                nextUri = 0
                Logger.i("HLS relay: master playlist received, ${mediaUris.size} media playlists to fetch")
            } else {
                val path = url.removePrefix(uriPrefix)
                val expanded = expandYtCondensed(text)
                if (expanded == null) {
                    Logger.w("HLS relay: media playlist $path rejected (invalid YT-EXT-CONDENSED-URL)")
                } else {
                    mediaPlaylists[path] = expanded
                    Logger.i("HLS relay: media playlist $path stored (${expanded.length} chars)")
                }
            }
            if (nextUri < mediaUris.size) nextRequest = mediaUris[nextUri++] else readyUrl = "$localPrefix/master.m3u8"
        }
        nextRequest?.let { sendFcup(it, requestIds.incrementAndGet()) }
        readyUrl?.let { onReady(it, startSeconds) }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        runCatching { server?.close() }
        server = null
        master = null
        mediaUris.clear(); mediaPlaylists.clear()
    }

    // ─── Local playlist server (loopback only) ────────────────────────────────────────────────

    private fun serve(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: Exception) { return }
            Thread({ handle(s) }, "HlsRelay-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                while (true) { if (readLine(input).isNullOrEmpty()) break }   // discard headers
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                val body: String? = synchronized(lock) {
                    if (path == "/master.m3u8") master else mediaPlaylists[path]
                        ?: mediaPlaylists.entries.firstOrNull { it.key.endsWith(path) || path.endsWith(it.key) }?.value
                }
                val out = s.getOutputStream()
                if (body == null) {
                    Logger.w("HLS relay: local request $path — not found")
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/x-mpegURL; charset=utf-8\r\n" +
                            "Access-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII) + bytes
                    )
                }
                out.flush()
            } catch (e: Exception) {
                Logger.d("HLS relay: local connection ended: ${e.message}")
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    // ─── Playlist helpers ─────────────────────────────────────────────────────────────────────

    /** Sender-side media playlist URIs (each starts with [uriPrefix] and ends at the first "m3u8"), in order. */
    private fun mediaUriList(masterText: String): List<String> {
        val out = LinkedHashSet<String>()
        var from = 0
        while (true) {
            val start = masterText.indexOf(uriPrefix, from)
            if (start < 0) break
            val m = masterText.indexOf("m3u8", start)
            if (m < 0) break
            out.add(masterText.substring(start, m + 4))
            from = m + 4
        }
        return out.toList()
    }

    companion object {
        /**
         * Expands a YouTube "condensed" media playlist (`#YT-EXT-CONDENSED-URL` with BASE-URI / PARAMS / PREFIX)
         * into ordinary segment URLs. Returns the text unchanged if it isn't condensed, or null if the tag is
         * invalid.
         */
        fun expandYtCondensed(pl: String): String? {
            val yt = pl.indexOf("#YT-EXT-CONDENSED-URL")
            val firstChunk = pl.indexOf("#EXTINF")
            if (yt < 0 || firstChunk < 0 || yt > firstChunk) return pl
            val tagEnd = pl.indexOf('\n', yt).let { if (it < 0) pl.length else it }
            val tag = pl.substring(yt, tagEnd)
            fun attr(name: String) = Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            val base = attr("BASE-URI")
            val params = attr("PARAMS")
            val prefix = attr("PREFIX")
            if (base.isNullOrEmpty() || params.isNullOrEmpty() || prefix.isNullOrEmpty()) return null
            val names = params.split(",")
            val chunks = Regex("#EXTINF").findAll(pl, firstChunk).count()
            val sb = StringBuilder(pl.length + chunks * (base.length + params.length + 8))
            sb.append(pl, 0, firstChunk)
            var pos = firstChunk
            for (c in 0 until chunks) {
                val start = pl.indexOf(prefix, pos)
                if (start < 0) return null
                sb.append(pl, pos, start).append(base).append('/')
                pos = start + prefix.length
                for ((i, name) in names.withIndex()) {
                    val end = when {
                        i != names.lastIndex -> pl.indexOf('/', pos).let { if (it < 0) return null else it + 1 }
                        c < chunks - 1 -> pl.indexOf("#EXTINF", pos).let { if (it < 0) return null else it }
                        else -> pl.length
                    }
                    sb.append(name).append('/').append(pl, pos, end)
                    pos = end
                }
            }
            return sb.toString()
        }

        /** Builds the XML-plist body of an FCUP `unhandledURLRequest` event (sent on the reverse channel). */
        fun fcupRequestXml(url: String, requestId: Int, sessionId: String): ByteArray = PlistCodec.encodeXml(
            mapOf(
                "sessionID" to 1L,
                "type" to "unhandledURLRequest",
                "request" to mapOf(
                    "FCUP_Response_ClientInfo" to 1L,
                    "FCUP_Response_ClientRef" to 40030004L,
                    "FCUP_Response_RequestID" to requestId.toLong(),
                    "FCUP_Response_URL" to url,
                    "sessionID" to 1L,
                    "FCUP_Response_Headers" to mapOf(
                        "X-Playback-Session-Id" to sessionId,
                        "User-Agent" to "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us",
                    ),
                ),
            )
        )

        /**
         * Builds the XML-plist body of a video playback-state `POST /event` (per the AirPlay video
         * spec's Events section): `category=video`, `sessionID`, `state` (loading/playing/paused/stopped).
         * The sender is documented to rely on these to know when the current item has ended and it may
         * send the next `/play` — PhairPlay's URL-video pipeline never sent them until now.
         */
        fun videoStateEventXml(sessionId: Long, state: String): ByteArray = PlistCodec.encodeXml(
            mapOf(
                "category" to "video",
                "sessionID" to sessionId,
                "state" to state,
            )
        )
    }
}
