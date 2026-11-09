package com.mychecklist.item.data

import com.mychecklist.item.domain.ItemAssignee
import com.mychecklist.item.domain.ItemAssigneeId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ItemAssigneeRepository : JpaRepository<ItemAssignee, ItemAssigneeId> {
    fun findByIdItemId(itemId: UUID): List<ItemAssignee>

    /** Items assigned to one user in one checklist: the "separate by user" view. */
    fun findByChecklistIdAndIdUserId(checklistId: UUID, userId: UUID): List<ItemAssignee>

    /**
     * Unassigns someone from every template item of one list, when they're removed or leave
     * (#11). Use `MemberService.remove` / `leave`, not this directly.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "DELETE FROM item_assignees WHERE checklist_id = :checklistId AND user_id = :userId",
        nativeQuery = true,
    )
    fun deleteForMember(@Param("checklistId") checklistId: UUID, @Param("userId") userId: UUID): Int
}
