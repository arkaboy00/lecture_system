package com.arka.lecturecapture.core

import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/** Manually advanced clock: wall and mono move together unless [reboot] is called. */
class FakeClock(
    var wall: Long = 1_791_270_902_000L, // 2026-10-06T10:15:02+03:00
    var monoMs: Long = 50_000L,
    var bootCount: Int? = 123,
    private val tz: ZoneId = ZoneId.of("Europe/Moscow"),
) : Clock {
    override fun wallMillis() = wall
    override fun mono() = monoMs
    override fun zone() = tz
    override fun boot() = bootCount

    fun advance(ms: Long) {
        wall += ms
        monoMs += ms
    }

    fun reboot(downtimeMs: Long) {
        wall += downtimeMs
        monoMs = 5_000L
        bootCount = bootCount?.plus(1)
    }
}

fun tempDir(): File = Files.createTempDirectory("lc-test").toFile().also { dir ->
    // deleteOnExit() cannot remove a non-empty directory.
    Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
}

fun records(clock: FakeClock, vararg bodies: Pair<Long, EventBody>): List<Record> =
    bodies.mapIndexed { i, (advance, body) ->
        clock.advance(advance)
        Record(i + 1L, clock.now(), body)
    }
