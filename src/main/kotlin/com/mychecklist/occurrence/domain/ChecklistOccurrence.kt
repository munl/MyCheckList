package com.mychecklist.occurrence.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One copy of the list for one day/week/... it's due. */
@Entity
@Table(name = "checklist_occurrences")
class ChecklistOccurrence(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    /** The day this occurrence is for, in the list's time zone. */
    @Column(name = "occurrence_date", nullable = false)
    var occurrenceDate: LocalDate,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    /** When it closes. Null for a one-time list with no deadline. */
    @Column(name = "due_at")
    var dueAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: OccurrenceStatus = OccurrenceStatus.OPEN

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    /** When every member with items had finished theirs. */
    @Column(name = "team_completed_at")
    var teamCompletedAt: Instant? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
