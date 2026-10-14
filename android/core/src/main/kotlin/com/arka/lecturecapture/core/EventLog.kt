package com.arka.lecturecapture.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** A line of events.jsonl that could not be decoded (torn tail after a crash, unknown event, ...). */
data class LineProblem(val line: Int, val reason: String, val text: String)

data class ReadResult(val records: List<Record>, val problems: List<LineProblem>)

object EventReader {
    fun read(file: File): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), emptyList())
        return parse(file.readText(Charsets.UTF_8))
    }

    fun parse(text: String): ReadResult {
        val records = ArrayList<Record>()
        val problems = ArrayList<LineProblem>()
        // A trailing chunk without '\n' is still parsed: the write may have completed
        // right before the crash. If it is torn, it simply fails to decode.
        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            try {
                records += EventCodec.decode(line)
            } catch (e: EventFormatException) {
                problems += LineProblem(index + 1, e.message ?: "bad line", line.take(200))
            }
        }
        return ReadResult(records, problems)
    }
}

/**
 * Append-only writer for events.jsonl. Every append is flushed and fsynced before returning.
 * Existing content is never rewritten: a torn last line is left in place and terminated with
 * '\n' so the next record starts on a fresh line.
 */
class EventLog private constructor(
    val file: File,
    private val out: FileOutputStream,
    private var lastSeq: Long,
) : Closeable {

    companion object {
        /** [lastSeq] is the highest seq already in the file (0 for a new file). */
        fun open(file: File, lastSeq: Long): EventLog {
            file.parentFile?.mkdirs()
            terminateLastLine(file)
            return EventLog(file, FileOutputStream(file, true), lastSeq)
        }

        private fun terminateLastLine(file: File) {
            if (!file.exists() || file.length() == 0L) return
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length() - 1)
                if (raf.read() != '\n'.code) {
                    raf.seek(raf.length())
                    raf.write('\n'.code)
                    raf.fd.sync()
                }
            }
        }
    }

    val seq: Long @Synchronized get() = lastSeq

    @Synchronized
    fun append(body: EventBody, stamp: Stamp): Record {
        val record = Record(lastSeq + 1, stamp, body)
        val bytes = (EventCodec.encode(record) + "\n").toByteArray(Charsets.UTF_8)
        out.write(bytes)
        out.flush()
        out.fd.sync()
        lastSeq = record.seq
        return record
    }

    @Synchronized
    override fun close() = out.close()
}
