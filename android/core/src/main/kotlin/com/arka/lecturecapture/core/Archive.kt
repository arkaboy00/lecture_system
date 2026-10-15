package com.arka.lecturecapture.core

enum class ArchiveSort { NEWEST, OLDEST }

/** One block of the archive list; [subject] is null when the list is not grouped. */
data class ArchiveSection(val subject: String?, val lectures: List<LectureSummary>)

/** Sorting, grouping and filtering of the lecture archive screen. */
object Archive {
    /**
     * [subject] keeps only that subject (case-insensitive), null keeps all. Grouped sections
     * are ordered by subject name (spelled as in their latest lecture); lectures inside every section follow [sort].
     */
    fun arrange(all: List<LectureSummary>, sort: ArchiveSort, grouped: Boolean, subject: String? = null): List<ArchiveSection> {
        val kept = if (subject == null) all else all.filter { it.subject.equals(subject, ignoreCase = true) }
        val byTime = compareBy<LectureSummary> { startMillis(it) }.thenBy { it.dir.name }
        val sorted = kept.sortedWith(if (sort == ArchiveSort.NEWEST) byTime.reversed() else byTime)
        if (!grouped) return if (sorted.isEmpty()) emptyList() else listOf(ArchiveSection(null, sorted))
        return sorted.groupBy { it.subject.trim().lowercase() }
            .map { (_, lectures) -> ArchiveSection(lectures.maxBy(::startMillis).subject.trim(), lectures) }
            .sortedBy { it.subject!!.lowercase() }
    }

    /** Subjects present in [all], each once (spelled as in its latest lecture), by name. */
    fun subjects(all: List<LectureSummary>): List<String> =
        all.sortedByDescending(::startMillis).map { it.subject.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** Lectures without a readable start (broken log) sort as the oldest. */
    private fun startMillis(lecture: LectureSummary): Long =
        lecture.started?.let { runCatching { Stamps.epochMillis(it.ts) }.getOrNull() } ?: Long.MIN_VALUE
}
