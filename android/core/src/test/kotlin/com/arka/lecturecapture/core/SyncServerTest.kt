package com.arka.lecturecapture.core

import com.arka.lecturecapture.core.sync.Ack
import com.arka.lecturecapture.core.sync.DiscoveryResponder
import com.arka.lecturecapture.core.sync.FileEntry
import com.arka.lecturecapture.core.sync.Manifest
import com.arka.lecturecapture.core.sync.PairResponse
import com.arka.lecturecapture.core.sync.SyncListener
import com.arka.lecturecapture.core.sync.SyncProtocol
import com.arka.lecturecapture.core.sync.SyncServer
import com.arka.lecturecapture.core.sync.SyncStore
import kotlinx.serialization.json.Json
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncServerTest {
    private val clock = FakeClock()
    private val root = File(tempDir(), "Lectures")
    private val store = LectureStore(root, clock)
    private val json = Json { ignoreUnknownKeys = true }

    private class MemoryStore : SyncStore {
        var token: String? = null
        val acks = ArrayList<Ack>()
        override fun token() = token
        override fun saveToken(token: String) { this.token = token }
        override fun saveAck(ack: Ack) { acks += ack }
    }

    private val syncStore = MemoryStore()
    private var done = 0
    private val server = SyncServer(
        root, syncStore,
        object : SyncListener { override fun onDone() { done++ } },
        device = "test", pairingCode = "123456", port = 0, bindAddress = InetAddress.getLoopbackAddress(),
    ).start()

    @AfterTest
    fun stop() = server.close()

    private class Response(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
        val text get() = body.toString(Charsets.UTF_8)
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = null, range: String? = null): Response {
        val conn = URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        range?.let { conn.setRequestProperty("Range", it) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        val headers = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        return Response(code, bytes, headers)
    }

    private fun pair(): String {
        val r = call("POST", "/pair", """{"code":"123456","client":"laptop"}""")
        assertEquals(200, r.code, r.text)
        return json.decodeFromString(PairResponse.serializer(), r.text).token
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun lectureWithFiles(): Lecture {
        val lecture = store.create("Сопромат", "Лекция 5")
        lecture.file("audio/001.ogg").writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        lecture.file("photos/101203_1.jpg").writeText("jpeg")
        lecture.file(".pending/1_2_3.jpg").writeText("unsorted")
        lecture.file("audio/002.ogg.part").writeText("partial")
        return lecture
    }

    @Test
    fun pairingNeedsTheShownCode() {
        assertEquals(401, call("GET", "/manifest").code)
        assertEquals(403, call("POST", "/pair", """{"code":"000000"}""").code)
        val token = pair()
        assertEquals(64, token.length)
        assertEquals(token, pair(), "pairing again returns the same token")
        assertEquals(401, call("GET", "/manifest", token = "wrong").code)
        assertEquals(200, call("GET", "/manifest", token = token).code)
        assertTrue(call("GET", "/hello").text.contains("\"paired\":true"))
    }

    @Test
    fun tooManyWrongCodesLockPairing() {
        repeat(5) { assertEquals(403, call("POST", "/pair", """{"code":"999999"}""").code) }
        assertEquals(429, call("POST", "/pair", """{"code":"123456"}""").code)
    }

    @Test
    fun manifestListsLectureFilesWithHashesAndSkipsUnsorted() {
        val lecture = lectureWithFiles()
        val token = pair()
        val manifest = json.decodeFromString(Manifest.serializer(), call("GET", "/manifest", token = token).text)
        val entry = manifest.lectures.single()
        assertEquals(lecture.dir.name, entry.folder)
        assertFalse(entry.ended)
        val paths = entry.files.map { it.path.substringAfter('/') }
        assertEquals(listOf("audio/001.ogg", "events.jsonl", "lecture.json", "photos/101203_1.jpg"), paths)
        val audio = entry.files.first { it.path.endsWith("001.ogg") }
        assertEquals(sha(lecture.file("audio/001.ogg").readBytes()), audio.sha256)
        lecture.close()
    }

    @Test
    fun fileDownloadResumesWithRange() {
        val lecture = lectureWithFiles()
        val token = pair()
        val path = URLEncoder.encode("${lecture.dir.name}/audio/001.ogg", "UTF-8")
        val whole = call("GET", "/file?path=$path", token = token)
        assertEquals(200, whole.code)
        assertEquals(200_000, whole.body.size)

        val tail = call("GET", "/file?path=$path", token = token, range = "bytes=150000-")
        assertEquals(206, tail.code)
        assertEquals("bytes 150000-199999/200000", tail.headers["Content-Range"])
        assertTrue(whole.body.copyOfRange(150_000, 200_000).contentEquals(tail.body))

        assertEquals(416, call("GET", "/file?path=$path", token = token, range = "bytes=200000-").code)
        lecture.close()
    }

    @Test
    fun pathsOutsideLectureFoldersAreRefused() {
        val lecture = lectureWithFiles()
        val token = pair()
        File(root.parentFile, "secret.txt").writeText("no")
        for (p in listOf("../secret.txt", "${lecture.dir.name}/../../secret.txt", "/etc/hosts", ".stignore",
            "${lecture.dir.name}/.pending/1_2_3.jpg", "${lecture.dir.name}\\events.jsonl")) {
            val code = call("GET", "/file?path=${URLEncoder.encode(p, "UTF-8")}", token = token).code
            assertEquals(403, code, p)
        }
        assertEquals(404, call("GET", "/file?path=${URLEncoder.encode("${lecture.dir.name}/nope", "UTF-8")}", token = token).code)
        lecture.close()
    }

    @Test
    fun ackAndDoneReachTheOwner() {
        val token = pair()
        val ack = Ack("2026-10-06_1015_x", listOf(FileEntry("2026-10-06_1015_x/events.jsonl", 10, "ab")))
        assertEquals(200, call("POST", "/ack", json.encodeToString(Ack.serializer(), ack), token).code)
        assertEquals(listOf(ack), syncStore.acks)
        assertEquals(200, call("POST", "/done", "", token).code)
        assertEquals(1, done)
    }

    @Test
    fun discoveryAnswersTheMagicWord() {
        DiscoveryResponder({ """{"port":${server.port}}""" }, port = 0).start().use { responder ->
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                val ask = SyncProtocol.DISCOVER_MAGIC.toByteArray()
                socket.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), responder.port))
                val reply = DatagramPacket(ByteArray(512), 512)
                socket.receive(reply)
                assertEquals("""{"port":${server.port}}""", String(reply.data, 0, reply.length))
            }
        }
    }
}
