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
     *
     * The items are locked first (`FOR UPDATE OF i`), so a tick that is saving at the same moment
     * finishes first, and Postgres then re-checks `completed_at IS NULL` on the ticked row: a
     * finished item keeps them (PR #33 review). Without the lock, the delete would decide
     * "unfinished" from before the tick.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "DELETE FROM occurrence_item_assignees " +
            "WHERE checklist_id = :checklistId AND user_id = :userId AND occurrence_item_id IN (" +
            "  SELECT i.id FROM occurrence_items i " +
            "  JOIN checklist_occurrences o ON o.id = i.occurrence_id " +
            "  JOIN occurrence_item_assignees a ON a.occurrence_item_id = i.id " +
            "  WHERE a.checklist_id = :checklistId AND a.user_id = :userId " +
            "  AND o.status = 'OPEN' AND i.completed_at IS NULL " +
            "  FOR UPDATE OF i" +
            ")",
        nativeQuery = true,
    )
    fun deleteUnfinishedOnOpenDays(@Param("checklistId") checklistId: UUID, @Param("userId") userId: UUID): Int
}
