package com.arka.lecturecapture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 8: photo type and redraw flag, notes, marks, lecture parts. */
class MarkupTest {
    private val clock = FakeClock()
    private val root = File(tempDir(), "Lectures")
    private val store = LectureStore(root, clock)

    @Test
    fun newEventsRoundTrip() {
        val bodies = listOf(
            PhotoTaken("photos/a.jpg", PhotoType.SLIDE, 1),
            PhotoRetyped("photos/a.jpg", PhotoType.NOTEBOOK),
            PhotoRedraw("photos/a.jpg", on = true),
            NoteAdded("формула на обороте"),
            NoteRemoved(4),
            Marked,
            Unmarked(6),
            LectureStart(subject = "Сопромат", title = "Лекция 5 ч.2", part = 2),
        )
        bodies.forEachIndexed { i, body ->
            val record = Record(i + 1L, clock.now(), body)
            assertEquals(record, EventCodec.decode(EventCodec.encode(record)))
        }
        val line = EventCodec.encode(Record(1, clock.now(), Marked))
        assertTrue(line.startsWith("""{"e":"mark","seq":1,"""), line)
        assertTrue(EventCodec.encode(Record(1, clock.now(), PhotoRetyped("photos/a.jpg", PhotoType.SLIDE))).contains(""""type":"slide""""))
    }

    @Test
    fun oldStartLineIsPartOne() {
        val record = EventCodec.decode("""{"e":"lecture_start","seq":1,"ts":"2026-10-06T10:15:02+03:00","mono":1,"v":1,"subject":"С","title":"Л"}""")
        assertEquals(1, LectureState.replay(listOf(record)).part)
    }

    @Test
    fun replayAppliesFlagsNotesAndMarks() {
        val state = LectureState.replay(
            records(
                clock,
                0L to LectureStart(subject = "Сопромат", title = "Лекция 5"),
                1_000L to PhotoTaken("photos/a.jpg", PhotoType.BOARD, 1),
                1_000L to PhotoRetyped("photos/a.jpg", PhotoType.SLIDE),
                1_000L to PhotoRedraw("photos/a.jpg", on = true),
                1_000L to NoteAdded("первая"),
                1_000L to NoteAdded("вторая"),
                1_000L to NoteRemoved(5),
                1_000L to Marked,
                1_000L to Marked,
                1_000L to Unmarked(9),
            ),
        )
        val photo = state.photos.single()
        assertEquals(PhotoType.SLIDE, photo.type)
        assertTrue(photo.redraw)
        assertEquals(listOf(true, false), state.notes.map { it.removed })
        assertEquals(listOf(false, true), state.marks.map { it.removed })
        val timeline = state.timeline()
        assertEquals(listOf("вторая"), timeline.filterIsInstance<TimelineItem.Noted>().map { it.note.text })
        assertEquals(listOf(8L), timeline.filterIsInstance<TimelineItem.Starred>().map { it.mark.seq })
        assertTrue(state.warnings.isEmpty(), state.warnings.toString())
    }

    @Test
    fun withdrawingUnknownNoteOrMarkWarns() {
        val state = LectureState.replay(
            records(clock, 0L to LectureStart(subject = "С", title = "Л"), 0L to NoteRemoved(42), 0L to Unmarked(1)),
        )
        assertEquals(2, state.warnings.size)
        assertTrue(state.notes.isEmpty() && state.marks.isEmpty())
    }

    @Test
    fun laterEditsOfAFinishedLecture() {
        val lecture = store.create("Сопромат", "Лекция 5")
        clock.advance(1_000)
        lecture.append(PhotoTaken("photos/a.jpg", PhotoType.BOARD, 1))
        lecture.append(NoteAdded("заметка"))
        lecture.append(Marked)
        val noteSeq = lecture.state.notes.single().seq
        val markSeq = lecture.state.marks.single().seq
        val dir = lecture.finish(LectureKind.LECTURE)
        val linesBefore = File(dir, Naming.EVENTS).readLines().size

        store.edit(dir, PhotoRetyped("photos/a.jpg", PhotoType.NOTEBOOK))
        store.edit(dir, PhotoRetyped("photos/a.jpg", PhotoType.NOTEBOOK)) // no change, not written
        store.edit(dir, PhotoRedraw("photos/a.jpg", on = true))
        store.edit(dir, NoteRemoved(noteSeq))
        store.edit(dir, Unmarked(markSeq))
        assertEquals(linesBefore + 4, File(dir, Naming.EVENTS).readLines().size)

        val state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
        assertEquals(PhotoType.NOTEBOOK, state.photos.single().type)
        assertTrue(state.photos.single().redraw)
        assertTrue(state.notes.single().removed && state.marks.single().removed)
        assertTrue(state.warnings.isEmpty(), "later edits are allowed after lecture_end: ${state.warnings}")

        assertFailsWith<IllegalArgumentException> { store.edit(dir, NoteAdded("после конца")) }
        assertFailsWith<IllegalStateException> { store.edit(dir, PhotoRedraw("photos/none.jpg", on = true)) }
    }

    @Test
    fun savePhotosWithPerFrameTypeAndRedraw() {
        val lecture = store.create("Сопромат", "Лекция 5")
        val frames = (1..3).map {
            clock.advance(1_000)
            val shot = lecture.shutterNow()
            val part = lecture.pendingPartFile(shot).also { f -> f.parentFile.mkdirs(); f.writeText("jpeg $it") }
            check(part.renameTo(pendingFile(part)))
            PendingFrame(pendingFile(part), shot)
        }
        val saved = lecture.savePhotos(
            keep = frames,
            type = PhotoType.BOARD,
            discard = emptyList(),
            typeOf = mapOf(frames[1].name to PhotoType.SLIDE),
            redraw = setOf(frames[2].name),
        )
        val photos = lecture.state.photos
        assertEquals(saved, photos.map { it.file })
        assertEquals(listOf(PhotoType.BOARD, PhotoType.SLIDE, PhotoType.BOARD), photos.map { it.type })
        assertEquals(listOf(false, false, true), photos.map { it.redraw })
        assertTrue(photos.all { File(lecture.dir, it.file).isFile })
    }

    @Test
    fun nextPartKeepsSubjectAndNumbersTitle() {
        val first = store.create("Материаловедение", "Лекция 15-окт")
        val firstDir = first.finish(LectureKind.LECTURE)
        clock.advance(600_000)
        val second = store.createNextPart(firstDir)
        assertEquals("Материаловедение", second.state.subject)
        assertEquals("Лекция 15-окт ч.2", second.state.title)
        assertEquals(2, second.state.part)
        assertEquals(2, second.meta.part)
        assertTrue(second.dir.name.endsWith("_Материаловедение_Лекция-15-окт-ч.2"), second.dir.name)
        val secondDir = second.finish(LectureKind.LECTURE)

        val third = store.createNextPart(secondDir)
        assertEquals("Лекция 15-окт ч.3", third.state.title)
        assertEquals(3, third.state.part)
        assertEquals(3, store.list().first { it.dir == third.dir }.part)
        assertFailsWith<IllegalArgumentException> { store.createNextPart(third.dir) }
    }

    @Test
    fun partTitles() {
        assertEquals("Лекция 5 ч.2", Naming.partTitle("Лекция 5", 2))
        assertEquals("Лекция 5 ч.3", Naming.partTitle("Лекция 5 ч.2", 3))
        assertEquals("Лекция 5 ч.3", Naming.partTitle("Лекция 5 ч 2", 3))
        assertEquals("Лекция 5", Naming.partTitle("Лекция 5 ч.2", 1))
        assertFalse(Naming.partTitle("Лекция 5", 2).contains("_"))
    }
}
