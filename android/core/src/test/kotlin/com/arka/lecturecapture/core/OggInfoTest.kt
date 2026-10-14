package com.arka.lecturecapture.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OggInfoTest {
    private val dir = tempDir()

    /** Minimal Ogg page; CRC is not checked by the reader, so it is left zero. */
    private fun page(granule: Long, body: ByteArray, seq: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0) // version
        out.write(0) // header type
        for (i in 0 until 8) out.write(((granule shr (8 * i)) and 0xFF).toInt())
        repeat(4) { out.write(0x11) } // serial
        for (i in 0 until 4) out.write((seq shr (8 * i)) and 0xFF)
        repeat(4) { out.write(0) } // crc
        val lacing = ArrayList<Int>()
        var left = body.size
        while (left >= 255) { lacing += 255; left -= 255 }
        lacing += left
        out.write(lacing.size)
        lacing.forEach { out.write(it) }
        out.write(body)
        return out.toByteArray()
    }

    private fun opusHead(preSkip: Int): ByteArray =
        "OpusHead".toByteArray() + byteArrayOf(1, 1, (preSkip and 0xFF).toByte(), (preSkip shr 8).toByte()) +
            byteArrayOf(0x80.toByte(), 0x3E, 0, 0) + byteArrayOf(0, 0, 0)

    private fun opusFile(vararg audioGranules: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(page(0, opusHead(312), 0))
        out.write(page(0, "OpusTags".toByteArray() + ByteArray(8), 1))
        audioGranules.forEachIndexed { i, g -> out.write(page(g, ByteArray(300) { 7 }, i + 2)) }
        return out.toByteArray()
    }

    @Test
    fun durationFromLastGranuleMinusPreSkip() {
        val file = File(dir, "a.ogg").apply { writeBytes(opusFile(48_312, 96_312, 144_312)) }
        val info = assertNotNull(OggInfo.read(file))
        assertTrue(info.isOpus)
        assertEquals(5, info.pages)
        assertEquals(3_000L, info.durationMs)
        assertEquals(0L, info.tailBytes)
    }

    @Test
    fun tornLastPageIsIgnored() {
        val full = opusFile(48_312, 96_312, 144_312)
        val file = File(dir, "torn.ogg").apply { writeBytes(full.copyOf(full.size - 100)) }
        val info = assertNotNull(OggInfo.read(file))
        assertEquals(4, info.pages)
        assertEquals(2_000L, info.durationMs)
        // Last page = 27 header + 2 lacing + 300 body = 329 bytes, of which 229 survived.
        assertEquals(229L, info.tailBytes)
    }

    @Test
    fun headersOnlyMeansZeroDuration() {
        val file = File(dir, "empty.ogg").apply { writeBytes(opusFile()) }
        assertEquals(0L, OggInfo.read(file)?.durationMs)
    }

    @Test
    fun notOggIsNull() {
        assertNull(OggInfo.read(File(dir, "missing.ogg")))
        assertNull(OggInfo.read(File(dir, "x.ogg").apply { writeBytes(ByteArray(100) { 1 }) }))
        val wavLike = File(dir, "y.ogg").apply { writeBytes("RIFF".toByteArray() + ByteArray(60)) }
        assertNull(OggInfo.read(wavLike))
    }

    @Test
    fun nonOpusOggHasNoDuration() {
        val file = File(dir, "v.ogg").apply { writeBytes(page(1000, "\u0001vorbis".toByteArray() + ByteArray(30), 0)) }
        val info = assertNotNull(OggInfo.read(file))
        assertFalse(info.isOpus)
        assertEquals(0L, info.durationMs)
    }
}
