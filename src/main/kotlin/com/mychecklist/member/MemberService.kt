package com.mychecklist.member

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.data.ChecklistRepository
import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.error.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MemberService(
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
) {

    /**
     * Adds someone to a list. If they were a member before (soft removed), their old row is
     * restored so their stars and streaks come back. Use this instead of saving a new
     * [ChecklistMember], which would overwrite that row.
     *
     * A restored row gets [role], so it never keeps an old OWNER role. Someone who is already
     * an active member is left as they are: their role only changes through the members API.
     *
     * Owners can't be added this way; they only come from creating a list or a transfer.
     * Nobody can join an archived list (409), and a list that doesn't exist gives 404.
     * The caller isn't a member yet, so [com.mychecklist.checklist.ChecklistAccessService.requireAccess] can't check this.
     */
    @Transactional
    fun addOrRestore(checklistId: UUID, userId: UUID, role: MemberRole): ChecklistMember {
        require(role != MemberRole.OWNER) { "Owners come from creating a list or a transfer, not from joining" }
        val checklist = checklists.findByIdOrNull(checklistId) ?: throw NotFoundException("Checklist not found")
        if (checklist.archivedAt != null) {
            throw ConflictException("This checklist is archived. Unarchive it to make changes.")
        }
        val id = ChecklistMemberId(checklistId, userId)
        val existing = members.findByIdOrNull(id) ?: return members.save(ChecklistMember(id, role))
        if (existing.removedAt == null) return existing
        existing.removedAt = null
        existing.role = role
        return members.save(existing)
    }
}
