package com.mychecklist.occurrence.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/** An item as it was when its occurrence was created. Completion lives here. */
@Entity
@Table(name = "occurrence_items")
class OccurrenceItem(
    @Column(name = "occurrence_id", nullable = false)
    var occurrenceId: UUID,

    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(nullable = false, length = 500)
    var title: String,

    @Column(nullable = false)
    var position: Int,

    @Column
    var notes: String? = null,

    /** The template item it was copied from (null if that was deleted since). */
    @Column(name = "template_item_id")
    var templateItemId: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    /** Whoever ticked it, which may be someone helping. It still counts for the assignees. */
    @Column(name = "completed_by")
    var completedBy: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    /** Optimistic locking: an update based on a stale copy fails instead of silently overwriting. */
    @Version
    @Column(nullable = false)
    var version: Long = 0
}
