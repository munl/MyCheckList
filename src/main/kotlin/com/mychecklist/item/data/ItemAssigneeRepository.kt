package com.mychecklist.item.data

import com.mychecklist.item.domain.ItemAssignee
import com.mychecklist.item.domain.ItemAssigneeId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ItemAssigneeRepository : JpaRepository<ItemAssignee, ItemAssigneeId> {
    fun findByIdItemId(itemId: UUID): List<ItemAssignee>

    /** Items assigned to one user in one checklist: the "separate by user" view. */
    fun findByChecklistIdAndIdUserId(checklistId: UUID, userId: UUID): List<ItemAssignee>
}
