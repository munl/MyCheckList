package com.mychecklist.reward.data

import com.mychecklist.reward.domain.Reward
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RewardRepository : JpaRepository<Reward, UUID> {
    fun findByChecklistIdAndDeletedAtIsNullOrderByStarCostAsc(checklistId: UUID): List<Reward>
}
