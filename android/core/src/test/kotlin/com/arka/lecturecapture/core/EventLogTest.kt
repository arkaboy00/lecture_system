package com.arka.lecturecapture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLogTest {
    private val clock = FakeClock()

    @Test
    fun appendedRecordsReadBack() {
        val file = File(tempDir(), Naming.EVENTS)
        val written = EventLog.open(file, 0).use { log ->
            listOf(
                log.append(LectureStart(subject = "Термех", title = "Лекция 1"), clock.now()),
                log.append(AudioStart("audio/001.ogg"), clock.now()),
            )
        }
        val read = EventReader.read(file)
        assertEquals(written, read.records)
        assertEquals(listOf(1L, 2L), read.records.map { it.seq })
        assertTrue(read.problems.isEmpty())
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test
    fun seqContinuesAfterReopen() {
        val file = File(tempDir(), Naming.EVENTS)
        EventLog.open(file, 0).use { it.append(AudioStart("audio/001.ogg"), clock.now()) }
        val last = EventReader.read(file).records.last().seq
        EventLog.open(file, last).use { it.append(AudioStop("audio/001.ogg", StopReason.USER), clock.now()) }
        assertEquals(listOf(1L, 2L), EventReader.read(file).records.map { it.seq })
    }

    @Test
    fun tornLastLineIsKeptSkippedAndTerminated() {
        val file = File(tempDir(), Naming.EVENTS)
        EventLog.open(file, 0).use { it.append(AudioStart("audio/001.ogg"), clock.now()) }
        val torn = """{"e":"audio_stop","seq":2,"ts":"2026-10-06T10:1"""
        file.appendText(torn) // crash in the middle of a write

        val before = EventReader.read(file)
        assertEquals(1, before.records.size)
        assertEquals(1, before.problems.size)
        assertEquals(2, before.problems.single().line)

        EventLog.open(file, before.records.last().seq).use {
            it.append(AudioStop("audio/001.ogg", StopReason.RECOVERED, durMs = 1000), clock.now())
        }
        val after = EventReader.read(file)
        assertEquals(listOf(1L, 2L), after.records.map { it.seq })
        assertEquals(StopReason.RECOVERED, (after.records.last().body as AudioStop).reason)
        assertEquals(1, after.problems.size, "torn line stays in the file and is still reported")
        assertTrue(file.readText().contains(torn + "\n"), "raw data is not rewritten")
    }

    @Test
    fun completeLastLineWithoutNewlineIsAccepted() {
        val file = File(tempDir(), Naming.EVENTS)
        val record = Record(1, clock.now(), AudioStart("audio/001.ogg"))
        file.writeText(EventCodec.encode(record))
        assertEquals(listOf(record), EventReader.read(file).records)
        EventLog.open(file, 1).use { it.append(AudioStop("audio/001.ogg", StopReason.USER), clock.now()) }
        assertEquals(2, EventReader.read(file).records.size)
    }

    @Test
    fun crlfAndBlankLinesAreTolerated() {
        val a = EventCodec.encode(Record(1, clock.now(), AudioStart("audio/001.ogg")))
        val read = EventReader.parse("$a\r\n\n")
        assertEquals(1, read.records.size)
        assertTrue(read.problems.isEmpty())
    }
}
