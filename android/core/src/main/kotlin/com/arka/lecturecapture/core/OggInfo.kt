package com.arka.lecturecapture.core

import java.io.File
import java.io.RandomAccessFile

/**
 * What can be learned about an Ogg/Opus file without decoding it: used after a crash to find
 * how much audio survived. Only complete pages are counted; a torn tail is reported separately.
 */
data class OggInfo(
    val pages: Int,
    /** Duration of the audio in complete pages, ms (Opus granule is always 48 kHz). */
    val durationMs: Long,
    /** Bytes after the last complete page (a page cut short by the crash). */
    val tailBytes: Long,
    val isOpus: Boolean,
) {
    companion object {
        private const val OPUS_RATE = 48_000L
        private const val HEADER = 27

        fun read(file: File): OggInfo? {
            if (!file.isFile || file.length() < HEADER) return null
            RandomAccessFile(file, "r").use { raf -> return scan(raf, file.length()) }
        }

        private fun scan(raf: RandomAccessFile, length: Long): OggInfo? {
            val header = ByteArray(HEADER)
            var pos = 0L
            var pages = 0
            var lastGranule = -1L
            var preSkip = 0L
            var isOpus = false
            var completeEnd = 0L
            while (pos + HEADER <= length) {
                raf.seek(pos)
                raf.readFully(header)
                if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
                    header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
                ) break
                val segments = header[26].toInt() and 0xFF
                if (pos + HEADER + segments > length) break
                val lacing = ByteArray(segments).also { raf.readFully(it) }
                val bodySize = lacing.sumOf { it.toInt() and 0xFF }
                val pageEnd = pos + HEADER + segments + bodySize
                if (pageEnd > length) break

                if (pages == 0 && bodySize >= 19) {
                    val body = ByteArray(19).also { raf.readFully(it) }
                    if (String(body, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                        isOpus = true
                        preSkip = ((body[10].toInt() and 0xFF) or ((body[11].toInt() and 0xFF) shl 8)).toLong()
                    }
                }
                val granule = readLongLe(header, 6)
                if (granule != -1L) lastGranule = granule
                pages++
                completeEnd = pageEnd
                pos = pageEnd
            }
            if (pages == 0) return null
            val samples = if (lastGranule > preSkip) lastGranule - preSkip else 0L
            val duration = if (isOpus) samples * 1000 / OPUS_RATE else 0L
            return OggInfo(pages, duration, length - completeEnd, isOpus)
        }

        private fun readLongLe(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return value
        }
    }
}
