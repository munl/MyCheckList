package com.mychecklist.reward.data

import com.mychecklist.reward.domain.RedemptionStatus
import com.mychecklist.reward.domain.RewardRedemption
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface RewardRedemptionRepository : JpaRepository<RewardRedemption, UUID> {
    fun findByChecklistIdAndStatusOrderByRequestedAtAsc(checklistId: UUID, status: RedemptionStatus): List<RewardRedemption>

    /**
     * Cancels all of someone's PENDING requests on one list and refunds them, in one statement:
     * each gets status CANCELLED (decided by [actorId]), a REDEMPTION_REFUND ledger row with
     * [reason], and the stars go back into their cached `star_balance`. Approved and rejected
     * requests aren't touched. For when they're removed or leave (#11); [actorId] is whoever
     * caused it. Use `MemberService.remove` / `leave`, not this directly.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "WITH cancelled AS (" +
            "  UPDATE reward_redemptions SET status = 'CANCELLED', decided_by = :actorId, decided_at = now() " +
            "  WHERE checklist_id = :checklistId AND requested_by = :userId AND status = 'PENDING' " +
            "  RETURNING id, star_cost" +
            "), refunds AS (" +
            "  INSERT INTO star_transactions (checklist_id, user_id, amount, type, redemption_id, reason, created_by) " +
            "  SELECT :checklistId, :userId, star_cost, 'REDEMPTION_REFUND', id, :reason, :actorId FROM cancelled " +
            "  RETURNING amount" +
            ") " +
            "UPDATE checklist_members SET star_balance = star_balance + (SELECT coalesce(sum(amount), 0) FROM refunds) " +
            "WHERE checklist_id = :checklistId AND user_id = :userId AND EXISTS (SELECT 1 FROM refunds)",
        nativeQuery = true,
    )
    fun cancelPendingAndRefund(
        @Param("checklistId") checklistId: UUID,
        @Param("userId") userId: UUID,
        @Param("actorId") actorId: UUID,
        @Param("reason") reason: String,
    ): Int
}
