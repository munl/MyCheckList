package com.mychecklist.occurrence.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class OccurrenceMemberResultId(
    @Column(name = "occurrence_id", nullable = false)
    var occurrenceId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

/**
 * How one person did in one occurrence. Only people with items get a row; no row means
 * the day is skipped for their streak. Updated live as items are ticked, settled at close.
 */
@Entity
@Table(name = "occurrence_member_results")
class OccurrenceMemberResult(
    @EmbeddedId
    var id: OccurrenceMemberResultId,

    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "assigned_count", nullable = false)
    var assignedCount: Int,
) {
    @Column(name = "completed_count", nullable = false)
    var completedCount: Int = 0

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "stars_awarded", nullable = false)
    var starsAwarded: Int = 0

    @Column(name = "team_bonus_awarded", nullable = false)
    var teamBonusAwarded: Int = 0

    @Column(name = "streak_after", nullable = false)
    var streakAfter: Int = 0

    val isComplete: Boolean get() = completedCount >= assignedCount
}
