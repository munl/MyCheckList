package com.mychecklist.checklist.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Embeddable
data class ChecklistMemberId(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

@Entity
@Table(name = "checklist_members")
class ChecklistMember(
    @EmbeddedId
    var id: ChecklistMemberId,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var role: MemberRole,
) {
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant? = null

    // Cached totals. star_transactions is the source of truth; these are updated in the
    // same transaction as each ledger row so reads stay fast.

    @Column(name = "star_balance", nullable = false)
    var starBalance: Int = 0

    @Column(name = "current_streak", nullable = false)
    var currentStreak: Int = 0

    @Column(name = "longest_streak", nullable = false)
    var longestStreak: Int = 0

    /** occurrence_date of the last occurrence this member finished. */
    @Column(name = "last_completed_date")
    var lastCompletedDate: LocalDate? = null

    /** Set when they leave or are removed (soft removal). Null while they're a member. */
    @Column(name = "removed_at")
    var removedAt: Instant? = null
}
