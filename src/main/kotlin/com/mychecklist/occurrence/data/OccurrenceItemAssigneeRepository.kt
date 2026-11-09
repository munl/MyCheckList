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
     * All their items on open days are locked first, finished or not (`FOR NO KEY UPDATE OF i`),
     * and "unfinished" is decided only after that: a tick or untick saving at the same moment
     * finishes first, and its result counts (PR #33 review). Filtering before the lock would skip
     * an item that's being unticked. `NO KEY UPDATE` still waits for a tick, but doesn't block
     * someone else being assigned to the same item. Lock order: the caller has already locked the
     * member row (`ChecklistMemberRepository.markRemoved`); member rows come before item rows.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        // A MATERIALIZED CTE, so the planner can't move `completed_at IS NULL` inside the locking
        // query (where it would filter on the old row before waiting).
        value = "WITH locked AS MATERIALIZED (" +
            "  SELECT i.id, i.completed_at FROM occurrence_items i " +
            "  JOIN checklist_occurrences o ON o.id = i.occurrence_id " +
            "  JOIN occurrence_item_assignees a ON a.occurrence_item_id = i.id " +
            "  WHERE a.checklist_id = :checklistId AND a.user_id = :userId AND o.status = 'OPEN' " +
            "  FOR NO KEY UPDATE OF i" +
            ") " +
            "DELETE FROM occurrence_item_assignees " +
            "WHERE checklist_id = :checklistId AND user_id = :userId " +
            "AND occurrence_item_id IN (SELECT id FROM locked WHERE completed_at IS NULL)",
        nativeQuery = true,
    )
    fun deleteUnfinishedOnOpenDays(@Param("checklistId") checklistId: UUID, @Param("userId") userId: UUID): Int
}
