package com.mychecklist.reward.domain

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
import java.util.UUID

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
