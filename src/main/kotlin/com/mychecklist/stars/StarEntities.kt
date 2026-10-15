package com.mychecklist.stars

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

enum class StarTransactionType {
    /** Earned by finishing your items in an occurrence (1–4★ depending on streak). */
    DAILY_COMPLETION,

    /** Earned when everyone with items finished (1–3★ depending on team streak). */
    TEAM_BONUS,

    /** Added or removed by an owner/editor. Needs a reason. */
    ADJUSTMENT,

    /** Held when a reward is requested (negative). */
    REDEMPTION,

    /** Given back when a redemption is rejected or cancelled. */
    REDEMPTION_REFUND,
}

enum class RedemptionStatus { PENDING, APPROVED, REJECTED, CANCELLED }

/**
 * The star ledger. Every star earned or spent is one row; balances are sums of rows
 * that aren't voided. Rows are never edited, except that an award is voided (not deleted)
 * if an item is unticked before its occurrence closes.
 */
@Entity
@Table(name = "star_transactions")
class StarTransaction(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    /** Positive = earned/refunded, negative = spent/removed. Never 0. */
    @Column(nullable = false)
    var amount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: StarTransactionType,

    @Column(name = "occurrence_id")
    var occurrenceId: UUID? = null,

    @Column(name = "redemption_id")
    var redemptionId: UUID? = null,

    @Column
    var reason: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null
}

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

/**
 * A request to spend stars on a reward. Stars are held when requested (a REDEMPTION
 * ledger row) and refunded if rejected/cancelled. [starCost] is copied at request time.
 */
@Entity
@Table(name = "reward_redemptions")
class RewardRedemption(
    @Column(name = "reward_id", nullable = false)
    var rewardId: UUID,

    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "requested_by", nullable = false)
    var requestedBy: UUID,

    @Column(name = "star_cost", nullable = false)
    var starCost: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: RedemptionStatus = RedemptionStatus.PENDING

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    var requestedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: UUID? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column
    var note: String? = null
}
