package com.arka.lecturecapture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LectureTest {
    private val clock = FakeClock()
    private val root = File(tempDir(), "Lectures")
    private val store = LectureStore(root, clock)

    @Test
    fun createLaysOutFolder() {
        val lecture = store.create("Сопромат", "Лекция 5", device = "test")
        val dir = lecture.dir
        assertEquals("2026-10-06_1015_Сопромат_Лекция-5", dir.name)
        for (sub in listOf("audio", "photos", ".pending")) assertTrue(File(dir, sub).isDirectory, sub)
        assertTrue(File(root, ".nomedia").exists())

        val records = EventReader.read(File(dir, Naming.EVENTS)).records
        assertEquals(LectureStart(subject = "Сопромат", title = "Лекция 5", device = "test"), records.single().body)
        assertEquals("2026-10-06T10:15:02+03:00", records.single().stamp.ts)

        val meta = assertNotNull(MetaFile.read(dir))
        assertEquals("Сопромат", meta.subject)
        assertEquals(LectureKind.LECTURE, meta.kind)
        assertNull(meta.ended)
        assertFalse(File(dir, "lecture.json.tmp").exists())
        lecture.close()
    }

    @Test
    fun reopenAfterCrashRestoresSameState() {
        val lecture = store.create("Термех", "Лекция 1")
        clock.advance(1_000)
        lecture.append(AudioStart("audio/001.ogg"))
        clock.advance(5_000)
        lecture.append(PhotoTaken("photos/101508_1.jpg", PhotoType.BOARD, 1))
        val live = lecture.state
        // No close(): simulate the process being killed.

        val reopened = store.open(lecture.dir)
        assertEquals(live, reopened.state)
        assertTrue(reopened.readProblems.isEmpty())
        reopened.append(AudioStop("audio/001.ogg", StopReason.RECOVERED, durMs = 5_000))
        assertEquals(4L, reopened.state.lastSeq)
        reopened.close()
    }

    @Test
    fun danglingSegmentIsClosedAtTheEndOfItsAudio() {
        val lecture = store.create("Термех", "Лекция 1")
        clock.advance(1_000)
        val file = lecture.nextAudioFile()
        assertEquals("audio/001.ogg", file)
        lecture.append(AudioStart(file))
        val start = lecture.state.recording!!.start
        clock.advance(600_000) // process died somewhere in here, noticed 10 minutes later

        val reopened = store.open(lecture.dir)
        val stop = reopened.closeDanglingAudio { 61_500L }
        assertEquals(AudioStop(file, StopReason.RECOVERED, durMs = 61_500L), stop)
        val segment = reopened.state.segments.single()
        assertEquals(start.mono + 61_500, segment.stop!!.mono)
        assertEquals("2026-10-06T10:16:04+03:00", segment.stop!!.ts) // 10:15:03 + 61.5 s, seconds precision
        assertNull(reopened.state.recording)
        assertNull(reopened.closeDanglingAudio { 1L }, "nothing left to close")
        reopened.close()
    }

    @Test
    fun danglingSegmentWithoutReadableFileIsStampedNow() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.append(AudioStart(lecture.nextAudioFile()))
        clock.advance(5_000)
        lecture.closeDanglingAudio() // default reader: file does not exist
        val segment = lecture.state.segments.single()
        assertNull(segment.durMs)
        assertEquals(clock.monoMs, segment.stop!!.mono)
        lecture.close()
    }

    @Test
    fun nextAudioFileSkipsFilesOnDisk() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.file("audio/001.ogg").writeText("orphan")
        assertEquals("audio/002.ogg", lecture.nextAudioFile())
        lecture.close()
    }

    @Test
    fun missingMetaIsRebuiltFromLog() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.close()
        File(lecture.dir, Naming.META).delete()
        val reopened = store.open(lecture.dir)
        assertEquals("Термех", reopened.meta.subject)
        assertNotNull(MetaFile.read(lecture.dir))
        reopened.close()
    }

    @Test
    fun finishMarksEndedAndRenamesFolderWhenNamesChanged() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.rename(title = "Лекция 2")
        assertEquals("Лекция 2", MetaFile.read(lecture.dir)?.title)

        val finalDir = lecture.finish(LectureKind.SEMINAR)
        assertFalse(lecture.isOpen)
        assertEquals("2026-10-06_1015_Термех_Лекция-2", finalDir.name)
        val meta = assertNotNull(MetaFile.read(finalDir))
        assertEquals(LectureKind.SEMINAR, meta.kind)
        assertNotNull(meta.ended)

        val bodies = EventReader.read(File(finalDir, Naming.EVENTS)).records.map { it.body }
        assertEquals(listOf(MetaChanged(title = "Лекция 2"), LectureEnd(LectureKind.SEMINAR)), bodies.drop(1))
    }

    @Test
    fun finishKeepsFolderWhenNamesUnchanged() {
        val first = store.create("Термех", "Лекция 1")
        first.close()
        val second = store.create("Термех", "Лекция 1") // same minute → suffix
        assertEquals("2026-10-06_1015_Термех_Лекция-1_2", second.dir.name)
        assertEquals("2026-10-06_1015_Термех_Лекция-1_2", second.finish(LectureKind.LECTURE).name)
    }

    @Test
    fun deleteErasesOnlyUnfinishedLectureFolders() {
        val unfinished = store.create("Термех", "Лекция 1")
        unfinished.append(AudioStart("audio/001.ogg"))
        clock.advance(90_000)
        unfinished.append(AudioStop("audio/001.ogg", StopReason.USER, durMs = 89_500))
        unfinished.close()
        assertEquals(89_500L, store.list().single().audioMs)

        val finished = store.create("Сопромат", "Лекция 2")
        val finishedDir = finished.finish(LectureKind.LECTURE)

        store.delete(unfinished.dir)
        assertFalse(unfinished.dir.exists())
        assertFailsWith<IllegalArgumentException> { store.delete(finishedDir) }
        assertTrue(finishedDir.exists())
        assertFailsWith<IllegalArgumentException> { store.delete(tempDir()) }
    }

    @Test
    fun storeListsUnfinishedAndSubjects() {
        store.create("Сопромат", "Лекция 1").finish(LectureKind.LECTURE)
        clock.advance(86_400_000)
        store.create("Термех", "Лекция 3").close()
        File(root, "random-folder").mkdirs()

        val all = store.list()
        assertEquals(2, all.size)
        assertEquals("Термех", all.first().subject, "newest first")
        assertEquals(listOf("Термех"), store.unfinished().map { it.subject })
        assertEquals(listOf("Термех", "Сопромат"), store.subjects())
    }
}
