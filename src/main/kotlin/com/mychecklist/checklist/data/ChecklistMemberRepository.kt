package com.mychecklist.checklist.data

import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import org.springframework.data.jpa.repository.JpaRepository
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
}
