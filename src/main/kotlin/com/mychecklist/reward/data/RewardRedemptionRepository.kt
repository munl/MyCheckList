package com.mychecklist.reward.data

import com.mychecklist.reward.domain.RedemptionStatus
import com.mychecklist.reward.domain.RewardRedemption
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RewardRedemptionRepository : JpaRepository<RewardRedemption, UUID> {
    fun findByChecklistIdAndStatusOrderByRequestedAtAsc(checklistId: UUID, status: RedemptionStatus): List<RewardRedemption>
}
