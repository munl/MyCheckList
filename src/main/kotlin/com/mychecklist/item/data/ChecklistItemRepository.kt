package com.mychecklist.item.data

import com.mychecklist.item.domain.ChecklistItem
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChecklistItemRepository : JpaRepository<ChecklistItem, UUID> {
    fun findByChecklistIdOrderByPositionAsc(checklistId: UUID): List<ChecklistItem>
}
