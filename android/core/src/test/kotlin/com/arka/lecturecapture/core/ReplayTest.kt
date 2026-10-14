package com.arka.lecturecapture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTest {
    private val clock = FakeClock()
    private val start = 0L to LectureStart(subject = "Сопромат", title = "Лекция 5")

    @Test
    fun segmentsPausesAndGaps() {
        val state = LectureState.replay(
            records(
                clock, start,
                1_000L to AudioStart("audio/001.ogg"),
                60_000L to AudioStop("audio/001.ogg", StopReason.USER),
                90_000L to AudioStart("audio/002.ogg"),
            ),
        )
        assertEquals(2, state.segments.size)
        assertEquals("audio/002.ogg", state.recording?.file)
        assertEquals(3, state.nextAudioIndex)
        val gap = state.timeline().filterIsInstance<TimelineItem.Gap>().single()
        assertEquals(90_000L, gap.durationMs)
        assertTrue(state.warnings.isEmpty(), state.warnings.toString())
    }

    @Test
    fun silencingIsTrackedPerSegment() {
        val state = LectureState.replay(
            records(
                clock, start,
                0L to AudioStart("audio/001.ogg"),
                10_000L to AudioSilenced("audio/001.ogg"),
                5_000L to AudioSilenced("audio/001.ogg"), // repeated callback, ignored
                20_000L to AudioUnsilenced("audio/001.ogg"),
                10_000L to AudioSilenced("audio/001.ogg"),
                10_000L to AudioStop("audio/001.ogg", StopReason.USER), // closes the open silence
            ),
        )
        val silences = state.segments.single().silences
        assertEquals(2, silences.size)
        assertEquals(25_000L, Stamps.between(silences[0].start, silences[0].end!!))
        assertNotNull(silences[1].end)
        assertNull(state.recording)
    }

    @Test
    fun photoSavedLaterIsOrderedByShutterTime() {
        val shutter = clock.now()
        clock.advance(1_000)
        val list = records(
            clock, start,
            0L to AudioStart("audio/001.ogg"),
            30_000L to AudioSilenced("audio/001.ogg"),
        ) + Record(4, shutter, PhotoTaken("photos/101203_1.jpg", PhotoType.BOARD, 1))
        val state = LectureState.replay(list)
        // The photo was shot before audio_start even though it is the last line of the log.
        assertEquals(TimelineItem.Shot(state.photos.single()), state.timeline().first())
        assertEquals(PhotoType.BOARD, state.lastPhotoType)
        assertEquals(2, state.nextBatch)
    }

    @Test
    fun obsoleteAndRestoredToggle() {
        val base = records(
            clock, start,
            0L to PhotoTaken("photos/a.jpg", PhotoType.BOARD, 1),
            0L to PhotoTaken("photos/b.jpg", PhotoType.BOARD, 1),
            0L to PhotoObsolete("photos/a.jpg"),
        )
        val s1 = LectureState.replay(base)
        assertEquals(listOf(true, false), s1.photos.map { it.obsolete })
        val s2 = s1.reduce(Record(5, clock.now(), PhotoRestored("photos/a.jpg")))
        assertEquals(listOf(false, false), s2.photos.map { it.obsolete })
    }

    @Test
    fun metaAndEnd() {
        val state = LectureState.replay(
            records(
                clock, start,
                0L to MetaChanged(title = "Лекция 6"),
                0L to LectureEnd(LectureKind.SEMINAR),
            ),
        )
        assertEquals("Сопромат", state.subject)
        assertEquals("Лекция 6", state.title)
        assertEquals(LectureKind.SEMINAR, state.kind)
        assertTrue(state.isEnded)
    }

    @Test
    fun ordersAcrossRebootByBootThenMono() {
        val before = records(clock, start, 0L to AudioStart("audio/001.ogg"))
        clock.reboot(downtimeMs = 120_000)
        val after = listOf(
            Record(3, clock.now(), Recovered("phone rebooted")),
            Record(4, clock.now(), AudioStop("audio/001.ogg", StopReason.RECOVERED, durMs = 10_000)),
        )
        clock.advance(1_000)
        val photoAfter = Record(5, clock.now(), PhotoTaken("photos/x.jpg", PhotoType.NOTEBOOK, 1))
        val state = LectureState.replay(before + after + photoAfter)
        assertEquals(listOf(123, 124), state.boots)
        // mono after reboot (6 s) is smaller than before it, yet the photo is still last.
        assertTrue(state.timeline().last() is TimelineItem.Shot)
        assertEquals(1, state.recoveries.size)
    }

    @Test
    fun inconsistenciesBecomeWarningsNotFailures() {
        val state = LectureState.replay(
            records(
                clock, start,
                0L to AudioStop("audio/009.ogg", StopReason.USER),
                0L to PhotoObsolete("photos/none.jpg"),
                0L to AudioStart("audio/001.ogg"),
                0L to AudioStart("audio/002.ogg"),
                0L to LectureEnd(),
                0L to AudioStop("audio/002.ogg", StopReason.USER),
            ),
        )
        assertEquals(4, state.warnings.size, state.warnings.joinToString("\n"))
        assertFalse(state.segments[0].isOpen, "implicitly closed by the second audio_start")
        assertNull(state.recording)
    }

    @Test
    fun replayIsSameAsIncrementalReduce() {
        val list = records(
            clock, start,
            0L to AudioStart("audio/001.ogg"),
            1_000L to PhotoTaken("photos/a.jpg", PhotoType.BOARD, 1),
            1_000L to AudioStop("audio/001.ogg", StopReason.USER),
        )
        var incremental = LectureState()
        list.forEach { incremental = incremental.reduce(it) }
        assertEquals(LectureState.replay(list), incremental)
        assertEquals(4, incremental.lastSeq)
    }
}
