package com.mychecklist.checklist.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Foreign keys are mapped as plain UUID fields rather than JPA relationships.
// This keeps queries explicit and avoids lazy-loading surprises; join in repositories as needed.

@Entity
@Table(name = "checklists")
class Checklist(
    @Column(nullable = false, length = 200)
    var title: String,

    @Column
    var description: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,

    /** IANA zone (e.g. "America/Vancouver"). Decides when a "day" ends for everyone on the list. */
    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String = "UTC",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Consecutive occurrences where every member with items finished them. */
    @Column(name = "team_current_streak", nullable = false)
    var teamCurrentStreak: Int = 0

    @Column(name = "team_longest_streak", nullable = false)
    var teamLongestStreak: Int = 0

    @Column(name = "team_last_completed_date")
    var teamLastCompletedDate: LocalDate? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Column(name = "archived_at")
    var archivedAt: Instant? = null
}
