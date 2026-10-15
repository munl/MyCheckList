package com.mychecklist.checklist.data

import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChecklistMemberRepository : JpaRepository<ChecklistMember, ChecklistMemberId> {
    fun findByIdChecklistId(checklistId: UUID): List<ChecklistMember>

    /** All of one user's memberships (to show their role on each list). */
    fun findByIdUserId(userId: UUID): List<ChecklistMember>

    fun countByIdUserIdAndRole(userId: UUID, role: MemberRole): Long
}
