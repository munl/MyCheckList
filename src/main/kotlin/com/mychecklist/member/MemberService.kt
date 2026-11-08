package com.mychecklist.member

import com.mychecklist.checklist.ChecklistAccessService
import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.data.ChecklistRepository
import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.BadRequestException
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.error.NotFoundException
import com.mychecklist.member.api.MemberResponse
import com.mychecklist.user.data.UserRepository
import com.mychecklist.user.domain.User
import jakarta.persistence.EntityManager
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
    private val entityManager: EntityManager,
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
            .map { member -> member.toResponse(profiles.getValue(member.id.userId), showEmail = showEmails) }
    }

    /**
     * Owners (and super admins) switch a member between EDITOR and VIEWER. Asking for OWNER is
     * a 400, checked before permissions like the rest of the body: ownership only moves by transfer.
     * The target must be an active member (404) and not the owner (409). Setting the role they
     * already have changes nothing and isn't audited.
     */
    @Transactional
    fun changeRole(user: User, checklistId: UUID, targetUserId: UUID, role: MemberRole): MemberResponse {
        if (role == MemberRole.OWNER) {
            throw BadRequestException("Ownership can only be moved by a transfer")
        }
        val granted = access.requireAccess(checklistId, user, MemberRole.OWNER)
        val target = members.findByIdAndRemovedAtIsNull(ChecklistMemberId(checklistId, targetUserId))
            ?: throw NotFoundException("Member not found")
        if (target.role == MemberRole.OWNER) {
            throw ConflictException("The owner's role can't be changed. Transfer ownership instead.")
        }

        val oldRole = target.role
        if (oldRole != role) {
            // A targeted update, not `target.role = role`: saving the entity would write back the
            // whole row as it was read, undoing a leave or star change made in the meantime.
            if (members.updateRole(checklistId, targetUserId, role.name) == 0) {
                // They left, were removed (or the list deleted), or became the owner after we read
                // them. Read the row fresh: `refresh` would throw if it's gone, and a lookup would
                // return the stale copy this transaction already holds.
                entityManager.detach(target)
                val now = members.findByIdOrNull(target.id)
                if (now == null || now.removedAt != null) throw NotFoundException("Member not found")
                throw ConflictException("The owner's role can't be changed. Transfer ownership instead.")
            }
            // Reload just this row (new role, and anyone else's latest changes).
            entityManager.refresh(target)
            access.auditIfAdmin(granted, user, "CHANGE_MEMBER_ROLE", "userId=$targetUserId, from=$oldRole, to=$role")
        }
        // Only the owner and super admins get here, and both see emails.
        return target.toResponse(users.findByIdOrNull(targetUserId)!!, showEmail = true)
    }

    private fun ChecklistMember.toResponse(profile: User, showEmail: Boolean) = MemberResponse(
        userId = id.userId,
        displayName = profile.displayName,
        photoUrl = profile.photoUrl,
        email = profile.email.takeIf { showEmail },
        role = role,
        joinedAt = joinedAt,
        starBalance = starBalance,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
    )

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
        // Reload just this row (new role and join date), leaving the caller's other entities alone.
        entityManager.refresh(existing)
        return existing
    }
}
