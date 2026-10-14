package com.arka.lecturecapture.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamingTest {
    private val at = ZonedDateTime.of(2026, 10, 6, 10, 15, 2, 0, ZoneId.of("Europe/Moscow"))

    @Test
    fun folderNameMatchesSpec() {
        assertEquals("2026-10-06_1015_Сопромат_Лекция-5", Naming.folderName(at, "Сопромат", "Лекция 5"))
        assertEquals(
            "2026-10-06_1015_Кратные-интегралы-и-ряды_Семинар-2",
            Naming.folderName(at, "Кратные интегралы и ряды", "Семинар 2"),
        )
    }

    @Test
    fun sanitizeDropsForbiddenCharacters() {
        assertEquals("ЛекцияАБ-1.2", Naming.sanitize("Лекция\\/:*?\"<>|АБ  1.2"))
        assertEquals("a-b", Naming.sanitize("  a_b \t"))
        assertEquals("x", Naming.sanitize("...x..."))
        assertEquals("", Naming.sanitize(" ?? "))
        assertEquals("2026-10-06_1015", Naming.folderName(at, "", "?"))
        assertEquals(60, Naming.sanitize("я".repeat(100)).length)
    }

    @Test
    fun photoNamesAvoidCollisions() {
        assertEquals("photos/101502_1.jpg", Naming.photoFile(at, emptySet()))
        assertEquals("photos/101502_3.jpg", Naming.photoFile(at, setOf("photos/101502_1.jpg", "photos/101502_2.jpg")))
    }

    @Test
    fun pendingNameRoundTrips() {
        val shot = PendingShot(boot = 123, mono = 9_876_543L, wallMillis = 1_791_270_902_123L)
        assertEquals("123_9876543_1791270902123.jpg", Naming.pendingName(shot))
        assertEquals(shot, Naming.parsePending(Naming.pendingName(shot)))
        val noBoot = shot.copy(boot = null)
        assertEquals(noBoot, Naming.parsePending(Naming.pendingName(noBoot)))
        assertNull(Naming.parsePending("IMG_0001.jpg"))
        assertNull(Naming.parsePending("1_2_3.png"))
    }

    @Test
    fun audioFileIsZeroPadded() {
        assertEquals("audio/001.ogg", Naming.audioFile(1))
        assertEquals("audio/012.ogg", Naming.audioFile(12))
    }

    @Test
    fun nextTitleContinuesNumbering() {
        assertEquals("Лекция 1", Naming.nextNumberedTitle("Лекция", emptyList()))
        assertEquals("Лекция 6", Naming.nextNumberedTitle("Лекция", listOf("Лекция 5", "лекция 2", "Контрольная")))
    }
}
