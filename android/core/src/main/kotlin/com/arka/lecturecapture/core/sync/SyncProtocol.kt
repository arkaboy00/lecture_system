package com.arka.lecturecapture.core.sync

import kotlinx.serialization.Serializable

/**
 * Phone → laptop sync over the home LAN. The phone serves, the laptop pulls.
 *
 * Discovery: the laptop broadcasts [DISCOVER_MAGIC] to UDP [DISCOVERY_PORT]; the phone answers
 * the sender with a [Hello] JSON. HTTP on [HTTP_PORT] (or the port in the answer):
 *
 *   GET  /hello                     no auth          → Hello
 *   POST /pair    {code, client}    no auth          → {token}; 403 on a wrong code
 *   GET  /manifest                  Bearer token     → Manifest
 *   GET  /file?path=<folder/rel>    Bearer token     → bytes, `Range: bytes=N-` supported
 *   POST /ack     Ack               Bearer token     → the laptop holds these files
 *   POST /done                      Bearer token     → sync finished, the phone may stop
 */
object SyncProtocol {
    const val VERSION = 1
    const val DISCOVERY_PORT = 47811
    const val HTTP_PORT = 47812
    const val DISCOVER_MAGIC = "LECTURE-SYNC-DISCOVER 1"
}

@Serializable
data class Hello(val app: String = "lecture-capture", val v: Int = SyncProtocol.VERSION, val device: String, val port: Int, val paired: Boolean)

@Serializable
data class PairRequest(val code: String, val client: String = "")

@Serializable
data class PairResponse(val token: String)

@Serializable
data class FileEntry(val path: String, val size: Long, val sha256: String)

@Serializable
data class LectureEntry(val folder: String, val ended: Boolean, val created: String? = null, val files: List<FileEntry>)

@Serializable
data class Manifest(val v: Int = SyncProtocol.VERSION, val lectures: List<LectureEntry>)

/** Files of [folder] the laptop has verified (path relative to the root, sha256). */
@Serializable
data class Ack(val folder: String, val files: List<FileEntry>)
