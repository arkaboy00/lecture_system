package com.arka.lecturecapture.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A frame in `.pending/` waiting to be kept or discarded. */
data class PendingFrame(val file: File, val shot: PendingShot) {
    val name: String get() = file.name
}

/** Suffix of a frame CameraX is still writing; renamed to .jpg once the JPEG is complete. */
const val PART_SUFFIX = ".part"

/** Complete pending frames, oldest shutter first. Partial (.part) and foreign files are ignored. */
fun Lecture.pendingFrames(): List<PendingFrame> {
    val files = file(Naming.PENDING_DIR).listFiles() ?: return emptyList()
    return files.mapNotNull { f -> Naming.parsePending(f.name)?.let { PendingFrame(f, it) } }
        .sortedWith(compareBy({ state.chronoKey(stampOf(it.shot)).first }, { it.shot.mono }))
}

/** Where CameraX should write a frame shot at [shot]; rename to [pendingFile] when complete. */
fun Lecture.pendingPartFile(shot: PendingShot): File =
    file("${Naming.PENDING_DIR}/${Naming.pendingName(shot)}$PART_SUFFIX")

fun pendingFile(part: File): File = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))

/**
 * Keeps [keep] as one batch of [type] and deletes [discard].
 *
 * Per frame the `photo` event is written first, then the file is moved. A crash in between
 * leaves an event pointing at a missing file plus the frame still in `.pending/`;
 * [completePendingMoves] finishes the move by matching boot and mono. The event carries the
 * shutter stamp, not the time of saving.
 */
fun Lecture.savePhotos(keep: List<PendingFrame>, type: PhotoType, discard: List<PendingFrame>): List<String> {
    val saved = ArrayList<String>()
    synchronized(this) {
        val batch = state.nextBatch
        val taken = HashSet<String>().apply {
            state.photos.mapTo(this) { it.file }
            file(Naming.PHOTOS_DIR).list()?.forEach { add("${Naming.PHOTOS_DIR}/$it") }
        }
        for (frame in keep.sortedBy { it.shot.mono }) {
            val stamp = stampOf(frame.shot)
            val target = Naming.photoFile(Stamps.zoned(stamp.ts), taken)
            taken += target
            append(PhotoTaken(target, type, batch), stamp)
            move(frame.file, file(target))
            saved += target
        }
    }
    discard.forEach { it.file.delete() }
    return saved
}

/**
 * Removes files a crash leaves behind that hold no usable data: frames CameraX did not finish
 * (`.part`) and an unrenamed `lecture.json.tmp`. Complete frames and the log are never touched.
 */
fun Lecture.cleanupLeftovers(): List<String> {
    val removed = ArrayList<String>()
    file(Naming.PENDING_DIR).listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.forEach {
        if (it.delete()) removed += "${Naming.PENDING_DIR}/${it.name}"
    }
    file(Naming.META + ".tmp").takeIf { it.exists() }?.let { if (it.delete()) removed += it.name }
    return removed
}

/** Deletes every pending frame (the whole batch is cancelled). */
fun Lecture.discardPending() {
    file(Naming.PENDING_DIR).listFiles()?.forEach { it.delete() }
}

/**
 * Repairs a save interrupted between writing a `photo` event and moving its file.
 * Returns the photo paths that were completed.
 */
fun Lecture.completePendingMoves(): List<String> {
    val pending = pendingFrames()
    val done = ArrayList<String>()
    for (photo in state.photos) {
        val target = file(photo.file)
        if (target.exists()) continue
        val frame = pending.firstOrNull { it.shot.mono == photo.taken.mono && it.shot.boot == photo.taken.boot } ?: continue
        move(frame.file, target)
        done += photo.file
    }
    return done
}

private fun Lecture.stampOf(shot: PendingShot): Stamp = Stamps.make(shot.wallMillis, zone(), shot.mono, shot.boot)

private fun move(from: File, to: File) {
    to.parentFile?.mkdirs()
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}
