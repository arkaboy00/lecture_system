package com.arka.lecturecapture.core.sync

import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Persistent side of the sync: the pairing token and what the laptop has confirmed. */
interface SyncStore {
    fun token(): String?
    fun saveToken(token: String)
    fun saveAck(ack: Ack)
}

/** What the server reports to its owner (the Android service). */
interface SyncListener {
    /** Any request from the laptop: resets the idle timer. */
    fun onActivity() {}
    fun onPaired(client: String) {}
    fun onFileSent(path: String, bytes: Long) {}
    fun onAck(ack: Ack) {}
    /** The laptop says it has everything. */
    fun onDone() {}
}

/**
 * Minimal HTTP/1.1 server for [SyncProtocol]: one request per connection, files streamed
 * from disk, `Range: bytes=N-` / `N-M` for resuming. A dropped connection only ends that
 * request; the laptop retries.
 */
class SyncServer(
    private val root: File,
    private val store: SyncStore,
    private val listener: SyncListener,
    private val device: String,
    /** Six digits shown on the phone; only valid while this server runs. */
    val pairingCode: String = newPairingCode(),
    port: Int = SyncProtocol.HTTP_PORT,
    bindAddress: InetAddress? = null,
) : Closeable {
    private val socket = ServerSocket(port, 16, bindAddress).apply { reuseAddress = true }
    private val pool: ExecutorService = Executors.newFixedThreadPool(4) { r -> Thread(r, "sync-http").apply { isDaemon = true } }
    private val manifests = ManifestBuilder(root)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var closed = false
    @Volatile private var failedPairings = 0

    val port: Int get() = socket.localPort
    val bytesSent = AtomicLong()

    fun start(): SyncServer {
        Thread({
            while (!closed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // closed
                }
                pool.execute { serve(client) }
            }
        }, "sync-accept").apply { isDaemon = true }.start()
        return this
    }

    fun hello(): Hello = Hello(device = device, port = port, paired = store.token() != null)

    override fun close() {
        closed = true
        socket.close()
        pool.shutdownNow()
    }

    // --- request handling --------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val request = readRequest(input) ?: return
                listener.onActivity()
                try {
                    route(request, out)
                } catch (e: HttpError) {
                    respond(out, e.status, "text/plain; charset=utf-8", (e.message ?: "").toByteArray())
                }
            } catch (_: java.io.IOException) {
                // The laptop went away mid-request; it will retry.
            }
        }
    }

    private fun route(r: Request, out: OutputStream) {
        when {
            r.method == "GET" && r.path == "/hello" -> respondJson(out, json.encodeToString(Hello.serializer(), hello()))
            r.method == "POST" && r.path == "/pair" -> pair(r, out)
            r.method == "GET" && r.path == "/manifest" -> {
                authorize(r)
                respondJson(out, json.encodeToString(Manifest.serializer(), manifests.build()))
            }
            r.method == "GET" && r.path == "/file" -> {
                authorize(r)
                sendFile(r, out)
            }
            r.method == "POST" && r.path == "/ack" -> {
                authorize(r)
                val ack = parse(r) { json.decodeFromString(Ack.serializer(), it) }
                store.saveAck(ack)
                listener.onAck(ack)
                respondJson(out, """{"ok":true}""")
            }
            r.method == "POST" && r.path == "/done" -> {
                authorize(r)
                respondJson(out, """{"ok":true}""")
                listener.onDone()
            }
            else -> throw HttpError(404, "not found")
        }
    }

    private fun pair(r: Request, out: OutputStream) {
        if (failedPairings >= MAX_PAIRING_ATTEMPTS) throw HttpError(429, "too many wrong codes; restart sync on the phone")
        val req = parse(r) { json.decodeFromString(PairRequest.serializer(), it) }
        if (!MessageDigest.isEqual(req.code.trim().toByteArray(), pairingCode.toByteArray())) {
            failedPairings++
            throw HttpError(403, "wrong code")
        }
        val token = store.token() ?: newToken().also(store::saveToken)
        listener.onPaired(req.client)
        respondJson(out, json.encodeToString(PairResponse.serializer(), PairResponse(token)))
    }

    private fun authorize(r: Request) {
        val expected = store.token() ?: throw HttpError(401, "not paired")
        val given = r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: throw HttpError(401, "no token")
        if (!MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) throw HttpError(401, "bad token")
    }

    private fun sendFile(r: Request, out: OutputStream) {
        val rel = r.query["path"] ?: throw HttpError(400, "no path")
        val file = resolve(rel)
        val length = file.length()
        val range = r.headers["range"]
        var start = 0L
        var end = length - 1
        val partial = range != null
        if (range != null) {
            val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range.trim()) ?: throw HttpError(416, "bad range")
            start = m.groupValues[1].toLong()
            if (m.groupValues[2].isNotEmpty()) end = minOf(end, m.groupValues[2].toLong())
            if (start > length || (start == length && length > 0)) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
        }
        val count = (end - start + 1).coerceAtLeast(0)
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: $count\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$length\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(1 shl 16)
            var left = count
            while (left > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
                bytesSent.addAndGet(n.toLong())
            }
        }
        out.flush()
        listener.onFileSent(rel, count)
    }

    /** Only regular, non-hidden files inside a lecture folder under the root. */
    private fun resolve(rel: String): File {
        if (rel.startsWith("/") || rel.contains('\\') || rel.split('/').any { it == ".." || it.isEmpty() || it.startsWith(".") }) {
            throw HttpError(403, "forbidden path")
        }
        val file = File(root, rel).canonicalFile
        val base = root.canonicalFile
        if (!file.path.startsWith(base.path + File.separator) || file.parentFile == base) throw HttpError(403, "forbidden path")
        if (!file.isFile) throw HttpError(404, "no such file")
        return file
    }

    private fun <T> parse(r: Request, decode: (String) -> T): T = try {
        decode(r.body.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw HttpError(400, "bad json")
    }

    private fun respondJson(out: OutputStream, body: String) = respond(out, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; else -> "Error"
        }
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0], path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) return bytes.toString("ISO-8859-1").trimEnd('\r')
            if (bytes.size() > 8192) return null
            bytes.write(b)
        }
    }

    companion object {
        private const val MAX_BODY = 4 shl 20
        private const val MAX_PAIRING_ATTEMPTS = 5
        private val random = SecureRandom()

        fun newPairingCode(): String = "%06d".format(random.nextInt(1_000_000))

        fun newToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
