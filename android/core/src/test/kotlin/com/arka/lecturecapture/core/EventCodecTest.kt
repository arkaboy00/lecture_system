package com.arka.lecturecapture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventCodecTest {
    private val stamp = Stamp("2026-10-06T10:15:02+03:00", 123_456L, 123)

    private val allBodies: List<EventBody> = listOf(
        LectureStart(subject = "Сопромат", title = "Лекция 5", device = "M2010J19CG", app = "0.1"),
        MetaChanged(title = "Лекция 6"),
        MetaChanged(kind = LectureKind.SEMINAR),
        AudioStart("audio/001.ogg"),
        AudioStop("audio/001.ogg", StopReason.USER),
        AudioStop("audio/002.ogg", StopReason.RECOVERED, durMs = 61_500),
        AudioSilenced("audio/001.ogg"),
        AudioUnsilenced("audio/001.ogg"),
        PhotoTaken("photos/101203_1.jpg", PhotoType.BOARD, 3),
        PhotoTaken("photos/101203_2.jpg", PhotoType.NOTEBOOK, 4),
        PhotoObsolete("photos/101203_1.jpg"),
        PhotoRestored("photos/101203_1.jpg"),
        Recovered("process restarted"),
        Recovered(),
        LectureEnd(LectureKind.LECTURE),
    )

    @Test
    fun everyEventRoundTrips() {
        allBodies.forEachIndexed { i, body ->
            val record = Record(i + 1L, stamp, body)
            assertEquals(record, EventCodec.decode(EventCodec.encode(record)))
        }
    }

    @Test
    fun envelopeComesFirstAndMatchesSpec() {
        val line = EventCodec.encode(Record(7, stamp, PhotoTaken("photos/101203_1.jpg", PhotoType.BOARD, 3)))
        assertEquals(
            """{"e":"photo","seq":7,"ts":"2026-10-06T10:15:02+03:00","mono":123456,"boot":123,""" +
                """"file":"photos/101203_1.jpg","type":"board","batch":3}""",
            line,
        )
    }

    @Test
    fun cyrillicIsWrittenAsIs() {
        val line = EventCodec.encode(Record(1, stamp, LectureStart(subject = "Сопромат", title = "Лекция \"5\"")))
        assertTrue("Сопромат" in line, line)
        assertTrue("""Лекция \"5\"""" in line, line)
        assertFalse('\n' in line)
    }

    @Test
    fun lectureStartCarriesFormatVersion() {
        val line = EventCodec.encode(Record(1, stamp, LectureStart(subject = "A", title = "B")))
        assertTrue(""""v":1""" in line, line)
    }

    @Test
    fun nullsAreOmitted() {
        val line = EventCodec.encode(Record(1, stamp.copy(boot = null), AudioStop("audio/001.ogg", StopReason.USER)))
        assertFalse("boot" in line, line)
        assertFalse("dur_ms" in line, line)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val record = EventCodec.decode(
            """{"e":"audio_start","seq":2,"ts":"2026-10-06T10:15:02+03:00","mono":1,"file":"audio/001.ogg","future":true}""",
        )
        assertEquals(AudioStart("audio/001.ogg"), record.body)
        assertEquals(null, record.stamp.boot)
    }

    @Test
    fun badLinesAreRejected() {
        val bad = listOf(
            "",
            "not json",
            """{"e":"audio_start","seq":2,"ts":"2026-10-06T10:15:02+03:00","mono":1,"file":"a""", // torn
            """{"e":"teleport","seq":2,"ts":"2026-10-06T10:15:02+03:00","mono":1}""",
            """{"e":"audio_start","ts":"2026-10-06T10:15:02+03:00","mono":1,"file":"a"}""", // no seq
            """{"e":"photo","seq":2,"ts":"x","mono":1,"file":"a","type":"wall","batch":1}""",
        )
        for (line in bad) assertFailsWith<EventFormatException>(line) { EventCodec.decode(line) }
    }
}
