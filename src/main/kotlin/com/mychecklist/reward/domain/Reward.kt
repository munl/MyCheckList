package com.mychecklist.reward.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/** A reward that members of a repeating list can spend stars on. */
@Entity
@Table(name = "rewards")
class Reward(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(name = "star_cost", nullable = false)
    var starCost: Int,

    @Column
    var description: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    /** Soft delete: hidden from the list, but past redemptions still show it. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
}
