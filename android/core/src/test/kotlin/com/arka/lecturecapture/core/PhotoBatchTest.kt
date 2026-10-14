package com.arka.lecturecapture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoBatchTest {
    private val clock = FakeClock()
    private val store = LectureStore(File(tempDir(), "Lectures"), clock)

    /** Simulates CameraX: .part first, renamed when complete. */
    private fun Lecture.shoot(content: String = "jpeg"): PendingShot {
        val shot = shutterNow()
        val part = pendingPartFile(shot)
        part.writeText(content)
        check(part.renameTo(pendingFile(part)))
        clock.advance(700)
        return shot
    }

    @Test
    fun keepsSelectedWithShutterTimeAndDiscardsTheRest() {
        val lecture = store.create("Термех", "Лекция 1")
        clock.advance(60_000)
        val a = lecture.shoot("a")
        val b = lecture.shoot("b")
        val c = lecture.shoot("c")
        clock.advance(30_000) // the user looks at the catalog for a while
        val frames = lecture.pendingFrames()
        assertEquals(listOf(a, b, c), frames.map { it.shot })

        val saved = lecture.savePhotos(keep = listOf(frames[0], frames[2]), type = PhotoType.NOTEBOOK, discard = listOf(frames[1]))
        assertEquals(listOf("photos/101602_1.jpg", "photos/101603_1.jpg"), saved)
        assertEquals("a", lecture.file(saved[0]).readText())
        assertEquals("c", lecture.file(saved[1]).readText())
        assertTrue(lecture.pendingFrames().isEmpty())

        val photos = lecture.state.photos
        assertEquals(listOf(a.mono, c.mono), photos.map { it.taken.mono }, "stamped at the shutter, not at saving")
        assertEquals(setOf(1), photos.map { it.batch }.toSet())
        assertEquals(PhotoType.NOTEBOOK, lecture.state.lastPhotoType)
        lecture.close()
    }

    @Test
    fun batchesIncrementAndNamesInTheSameSecondDoNotCollide() {
        val lecture = store.create("Термех", "Лекция 1")
        val first = lecture.shutterNow()
        lecture.pendingPartFile(first).let { it.writeText("1"); it.renameTo(pendingFile(it)) }
        clock.advance(100) // same second
        val second = lecture.shutterNow()
        lecture.pendingPartFile(second).let { it.writeText("2"); it.renameTo(pendingFile(it)) }
        val saved = lecture.savePhotos(lecture.pendingFrames(), PhotoType.BOARD, emptyList())
        assertEquals(listOf("photos/101502_1.jpg", "photos/101502_2.jpg"), saved)

        lecture.shoot()
        lecture.savePhotos(lecture.pendingFrames(), PhotoType.BOARD, emptyList())
        assertEquals(listOf(1, 1, 2), lecture.state.photos.map { it.batch })
        lecture.close()
    }

    @Test
    fun partialFramesAndForeignFilesAreIgnored() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.pendingPartFile(lecture.shutterNow()).writeText("half a jpeg")
        lecture.file(".pending/notes.txt").writeText("?")
        assertTrue(lecture.pendingFrames().isEmpty())
        lecture.close()
    }

    @Test
    fun crashBetweenEventAndMoveIsCompletedOnReopen() {
        val lecture = store.create("Термех", "Лекция 1")
        val shot = lecture.shoot("board")
        // Do what savePhotos does, but "crash" before moving the file.
        val stamp = Stamps.make(shot.wallMillis, lecture.zone(), shot.mono, shot.boot)
        lecture.append(PhotoTaken("photos/101502_1.jpg", PhotoType.BOARD, 1), stamp)

        val reopened = store.open(lecture.dir)
        assertFalse(reopened.file("photos/101502_1.jpg").exists())
        assertEquals(listOf("photos/101502_1.jpg"), reopened.completePendingMoves())
        assertEquals("board", reopened.file("photos/101502_1.jpg").readText())
        assertTrue(reopened.pendingFrames().isEmpty())
        assertTrue(reopened.completePendingMoves().isEmpty(), "idempotent")
        reopened.close()
    }

    @Test
    fun cancelDiscardsEverything() {
        val lecture = store.create("Термех", "Лекция 1")
        lecture.shoot()
        lecture.pendingPartFile(lecture.shutterNow()).writeText("x")
        lecture.discardPending()
        assertEquals(0, lecture.file(".pending").listFiles()!!.size)
        assertTrue(lecture.state.photos.isEmpty())
        lecture.close()
    }
}
