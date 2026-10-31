package com.mychecklist.member

import com.mychecklist.checklist.ChecklistAccessService
import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.data.ChecklistRepository
import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.error.NotFoundException
import com.mychecklist.member.api.MemberResponse
import com.mychecklist.user.data.UserRepository
import com.mychecklist.user.domain.User
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MemberService(
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
    private val users: UserRepository,
    private val access: ChecklistAccessService,
) {

    /**
     * The list's active members: the owner first, then by join date, ties by user id.
     * Emails go only to the owner and super admins; everyone else gets them left out.
     */
    @Transactional(readOnly = true)
    fun list(user: User, checklistId: UUID): List<MemberResponse> {
        val granted = access.requireAccess(checklistId, user, MemberRole.VIEWER)
        val showEmails = granted.role == MemberRole.OWNER || user.isSuperAdmin
        val active = members.findByIdChecklistIdAndRemovedAtIsNull(checklistId)
        val profiles = users.findAllById(active.map { it.id.userId }).associateBy { it.id!! }

        return active
            // Postgres compares uuids byte by byte, which is the same as their text; UUID.compareTo isn't.
            .sortedWith(
                compareBy<ChecklistMember>({ it.role != MemberRole.OWNER }, { it.joinedAt }, { it.id.userId.toString() }),
            )
            .map { member ->
                val profile = profiles.getValue(member.id.userId)
                MemberResponse(
                    userId = member.id.userId,
                    displayName = profile.displayName,
                    photoUrl = profile.photoUrl,
                    email = profile.email.takeIf { showEmails },
                    role = member.role,
                    joinedAt = member.joinedAt,
                    starBalance = member.starBalance,
                    currentStreak = member.currentStreak,
                    longestStreak = member.longestStreak,
                )
            }
    }

    /**
     * Adds someone to a list. If they were a member before (soft removed), their old row is
     * restored so their stars and streaks come back, with a new join date. Use this instead of
     * saving a new [ChecklistMember], which would overwrite that row.
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
        members.restoreRemoved(checklistId, userId, role.name)
        return members.findByIdOrNull(id)!!
    }
}
