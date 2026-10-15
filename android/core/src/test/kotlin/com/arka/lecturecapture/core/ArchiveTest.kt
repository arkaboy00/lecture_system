package com.arka.lecturecapture.core

import com.arka.lecturecapture.core.sync.FileEntry
import com.arka.lecturecapture.core.sync.SyncMark
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveTest {
    private val clock = FakeClock()
    private val root = File(tempDir(), "Lectures")
    private val store = LectureStore(root, clock)

    private fun finished(subject: String, title: String, kind: LectureKind = LectureKind.LECTURE): File {
        val lecture = store.create(subject, title)
        clock.advance(1_000)
        lecture.append(PhotoTaken("photos/a.jpg", PhotoType.BOARD, 1))
        File(lecture.dir, "photos/a.jpg").writeText("jpeg")
        clock.advance(1_000)
        val dir = lecture.finish(kind)
        clock.advance(3_600_000)
        return dir
    }

    @Test
    fun arrangeSortsGroupsAndFilters() {
        finished("Термех", "Лекция 1")
        finished("сопромат", "Лекция 1")
        finished("Термех", "Лекция 2")
        finished("Сопромат", "Лекция 2")
        val all = store.list()

        val newest = Archive.arrange(all, ArchiveSort.NEWEST, grouped = false).single()
        assertEquals(null, newest.subject)
        assertEquals(listOf("Лекция 2", "Лекция 2", "Лекция 1", "Лекция 1"), newest.lectures.map { it.title })
        assertEquals("Сопромат", newest.lectures.first().subject)

        val grouped = Archive.arrange(all, ArchiveSort.OLDEST, grouped = true)
        assertEquals(listOf("Сопромат", "Термех"), grouped.map { it.subject })
        assertEquals(listOf("Лекция 1", "Лекция 2"), grouped[1].lectures.map { it.title })
        assertEquals(2, grouped[0].lectures.size, "subjects differing in case are one group")

        val only = Archive.arrange(all, ArchiveSort.NEWEST, grouped = false, subject = "СОПРОМАТ").single()
        assertEquals(2, only.lectures.size)
        assertTrue(Archive.arrange(all, ArchiveSort.NEWEST, grouped = true, subject = "Химия").isEmpty())

        assertEquals(listOf("Сопромат", "Термех"), Archive.subjects(all))
    }

    @Test
    fun amendRewritesNamesKindAndFolder() {
        val dir = finished("Термех", "Лекция 1")
        clock.advance(1_000)
        val moved = store.amend(dir, subject = "Теормех", title = "Лекция 1", kind = LectureKind.SEMINAR)

        assertEquals("2026-10-06_1015_Теормех_Лекция-1", moved.name)
        assertFalse(dir.exists())
        val meta = MetaFile.read(moved)!!
        assertEquals("Теормех", meta.subject)
        assertEquals(LectureKind.SEMINAR, meta.kind)
        assertTrue(meta.ended != null)

        val read = EventReader.read(File(moved, Naming.EVENTS))
        assertEquals(MetaChanged(subject = "Теормех", kind = LectureKind.SEMINAR), read.records.last().body)
        val state = LectureState.replay(read.records)
        assertTrue(state.warnings.isEmpty(), "later edits are not inconsistencies: ${state.warnings}")
        assertEquals(LectureKind.SEMINAR, state.kind)

        // Nothing changed: no event, no rename.
        val lines = File(moved, Naming.EVENTS).readLines().size
        assertEquals(moved, store.amend(moved, subject = "Теормех", title = "Лекция 1", kind = LectureKind.SEMINAR))
        assertEquals(lines, File(moved, Naming.EVENTS).readLines().size)
    }

    @Test
    fun photoMarksOnFinishedLecture() {
        val dir = finished("Термех", "Лекция 1")
        store.setObsolete(dir, "photos/a.jpg", true)
        store.setObsolete(dir, "photos/a.jpg", true)
        var state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
        assertTrue(state.photos.single().obsolete)
        assertEquals(1, state.photos.size)
        store.setObsolete(dir, "photos/a.jpg", false)
        state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
        assertFalse(state.photos.single().obsolete)
        assertTrue(state.warnings.isEmpty())
        assertFailsWith<IllegalStateException> { store.setObsolete(dir, "photos/none.jpg", true) }
    }

    @Test
    fun recordingAfterEndIsStillAWarning() {
        val dir = finished("Термех", "Лекция 1")
        store.open(dir).use { it.append(AudioStart("audio/001.ogg")) }
        val state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
        assertEquals(1, state.warnings.size)
    }

    @Test
    fun deletingFinishedNeedsTheFlag() {
        val dir = finished("Термех", "Лекция 1")
        assertFailsWith<IllegalArgumentException> { store.delete(dir) }
        assertTrue(dir.exists())
        store.delete(dir, finished = true)
        assertFalse(dir.exists())

        val open = store.create("Термех", "Лекция 2")
        assertFailsWith<IllegalArgumentException> { store.delete(open.dir, finished = true) }
        open.close()
        store.delete(open.dir)
        assertFalse(open.dir.exists())
        assertFailsWith<IllegalArgumentException> { store.delete(File(tempDir(), "x")) }
    }

    @Test
    fun syncMarkFollowsSizesAndRenames() {
        val dir = finished("Термех", "Лекция 1")
        fun ack(folder: File) = folder.walkTopDown().filter { it.isFile && !it.path.contains(".pending") }
            .map { FileEntry("${folder.name}/${it.relativeTo(folder).invariantSeparatorsPath}", it.length(), "") }.toList()

        assertEquals(SyncMark.NEVER, SyncMark.of(dir, null))
        val acked = ack(dir)
        assertEquals(SyncMark.SYNCED, SyncMark.of(dir, acked))

        store.setObsolete(dir, "photos/a.jpg", true)
        assertEquals(SyncMark.CHANGED, SyncMark.of(dir, acked))

        val moved = store.amend(dir, title = "Лекция 7")
        assertEquals(SyncMark.NEVER, SyncMark.of(moved, null))
        val renamedAck = SyncMark.renamed(acked, dir.name, moved.name)
        assertTrue(renamedAck.all { it.path.startsWith(moved.name + "/") })
        assertEquals(SyncMark.CHANGED, SyncMark.of(moved, renamedAck))
    }
}
