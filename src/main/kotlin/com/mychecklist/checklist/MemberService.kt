package com.mychecklist.checklist

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MemberService(
    private val members: ChecklistMemberRepository,
) {

    /**
     * Adds someone to a list. If they were a member before (soft removed), their old row is
     * restored so their stars and streaks come back. Use this instead of saving a new
     * [ChecklistMember], which would overwrite that row.
     *
     * A restored row gets [role], so it never keeps an old OWNER role. Someone who is already
     * an active member is left as they are: their role only changes through the members API.
     */
    @Transactional
    fun addOrRestore(checklistId: UUID, userId: UUID, role: MemberRole): ChecklistMember {
        val id = ChecklistMemberId(checklistId, userId)
        val existing = members.findByIdOrNull(id) ?: return members.save(ChecklistMember(id, role))
        if (existing.removedAt == null) return existing
        existing.removedAt = null
        existing.role = role
        return members.save(existing)
    }
}
