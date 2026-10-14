package com.arka.lecturecapture.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Source of time. The Android implementation reads elapsedRealtime and BOOT_COUNT. */
interface Clock {
    fun wallMillis(): Long
    fun mono(): Long
    fun zone(): ZoneId
    fun boot(): Int?
}

fun Clock.now(): Stamp = Stamps.make(wallMillis(), zone(), mono(), boot())

fun Clock.zonedNow(): ZonedDateTime = Instant.ofEpochMilli(wallMillis()).atZone(zone())

object Stamps {
    /** Always a numeric offset ("+00:00", never "Z"), seconds precision. */
    private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun make(wallMillis: Long, zone: ZoneId, mono: Long, boot: Int?): Stamp =
        Stamp(format(wallMillis, zone), mono, boot)

    fun format(wallMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(wallMillis).atZone(zone).format(TS_FORMAT)

    fun epochMillis(ts: String): Long = OffsetDateTime.parse(ts).toInstant().toEpochMilli()

    fun zoned(ts: String): ZonedDateTime = OffsetDateTime.parse(ts).toZonedDateTime()

    /** The same boot and offset, [ms] later. */
    fun plus(stamp: Stamp, ms: Long): Stamp = Stamp(
        ts = OffsetDateTime.parse(stamp.ts).plusNanos(ms * 1_000_000).format(TS_FORMAT),
        mono = stamp.mono + ms,
        boot = stamp.boot,
    )

    /**
     * Milliseconds from [a] to [b]: by [Stamp.mono] within one boot, by wall clock across boots.
     */
    fun between(a: Stamp, b: Stamp): Long =
        if (a.boot == b.boot) b.mono - a.mono else epochMillis(b.ts) - epochMillis(a.ts)
}
