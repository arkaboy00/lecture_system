package com.arka.lecturecapture.core.sync

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Answers the laptop's UDP broadcast with the [Hello] of this phone, so the laptop finds the
 * phone without typing an IP address. Only runs while sync is on.
 */
class DiscoveryResponder(
    private val hello: () -> String,
    port: Int = SyncProtocol.DISCOVERY_PORT,
) : Closeable {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(port))
    }
    @Volatile private var closed = false

    val port: Int get() = socket.localPort

    fun start(): DiscoveryResponder {
        Thread({
            val buffer = ByteArray(512)
            while (!closed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketException) {
                    break // closed
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                if (text != SyncProtocol.DISCOVER_MAGIC) continue
                val reply = hello().toByteArray(Charsets.UTF_8)
                try {
                    socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                } catch (_: Exception) {
                }
            }
        }, "sync-discovery").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
