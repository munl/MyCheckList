package com.mychecklist.occurrence.data

import com.mychecklist.occurrence.domain.OccurrenceItemAssignee
import com.mychecklist.occurrence.domain.OccurrenceItemAssigneeId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OccurrenceItemAssigneeRepository : JpaRepository<OccurrenceItemAssignee, OccurrenceItemAssigneeId> {
    fun findByIdOccurrenceItemId(occurrenceItemId: UUID): List<OccurrenceItemAssignee>

    /**
     * Unassigns someone from the **unfinished** items of every OPEN day of one list, when they're
     * removed or leave (#11). Finished items keep them (it shows who did the work), and closed
     * days are history, so both stay. Use `MemberService.remove` / `leave`, not this directly.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "DELETE FROM occurrence_item_assignees a " +
            "USING occurrence_items i, checklist_occurrences o " +
            "WHERE a.occurrence_item_id = i.id AND i.occurrence_id = o.id " +
            "AND a.checklist_id = :checklistId AND a.user_id = :userId " +
            "AND o.status = 'OPEN' AND i.completed_at IS NULL",
        nativeQuery = true,
    )
    fun deleteUnfinishedOnOpenDays(@Param("checklistId") checklistId: UUID, @Param("userId") userId: UUID): Int
}
