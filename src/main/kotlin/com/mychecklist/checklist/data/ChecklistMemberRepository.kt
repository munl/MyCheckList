package com.mychecklist.checklist.data

import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Removed members keep their row (`removedAt` is set), so every lookup here skips them.
 * Use the inherited `findById` only when you really need removed rows too.
 */
interface ChecklistMemberRepository : JpaRepository<ChecklistMember, ChecklistMemberId> {
    /** One person's membership of one list, unless they've been removed. */
    fun findByIdAndRemovedAtIsNull(id: ChecklistMemberId): ChecklistMember?

    fun findByIdChecklistIdAndRemovedAtIsNull(checklistId: UUID): List<ChecklistMember>

    /** All of one user's memberships (to show their role on each list). */
    fun findByIdUserIdAndRemovedAtIsNull(userId: UUID): List<ChecklistMember>

    fun countByIdUserIdAndRoleAndRemovedAtIsNull(userId: UUID, role: MemberRole): Long

    /**
     * Brings a removed member back with [role] and a new join date; stars and streaks stay.
     * `joined_at` isn't updatable through the entity, so this is the only place that changes it.
     * Does nothing if the row isn't removed. Use `MemberService.addOrRestore`, not this directly.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE checklist_members SET removed_at = NULL, role = :role, joined_at = now() " +
            "WHERE checklist_id = :checklistId AND user_id = :userId AND removed_at IS NOT NULL",
        nativeQuery = true,
    )
    fun restoreRemoved(
        @Param("checklistId") checklistId: UUID,
        @Param("userId") userId: UUID,
        @Param("role") role: String,
    ): Int
}
