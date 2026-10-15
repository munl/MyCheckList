package com.mychecklist.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val firebaseProjectId: String,
    val superAdminEmails: List<String> = emptyList(),
    val history: History = History(),
    val schedule: Schedule = Schedule(),
) {
    data class History(
        /** Days to keep item-level detail of past occurrences before compacting to summaries. */
        val fullDetailDays: Int = 90,
    )

    data class Schedule(
        /** Pause a repeating list after this many days with nobody opening it. */
        val autoPauseAfterDays: Int = 14,
    )

    /** Normalised set of super admin emails (lowercased, blanks removed). */
    val superAdminEmailSet: Set<String> =
        superAdminEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
