package com.mychecklist.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val firebaseProjectId: String,
    val superAdminEmails: List<String> = emptyList(),
) {
    /** Normalised set of super admin emails (lowercased, blanks removed). */
    val superAdminEmailSet: Set<String> =
        superAdminEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
