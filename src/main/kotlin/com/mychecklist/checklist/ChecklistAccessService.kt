package com.mychecklist.checklist

import com.mychecklist.admin.AdminAuditService
import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.data.ChecklistRepository
import com.mychecklist.checklist.domain.Checklist
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.error.ForbiddenException
import com.mychecklist.common.error.NotFoundException
import com.mychecklist.user.domain.User
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The result of a successful permission check.
 *
 * @property role the caller's own role in the checklist, or null if they aren't a member
 *               (only possible for a super admin).
 * @property viaSuperAdmin true when access was granted only because the caller is a super admin.
 */
data class ChecklistAccess(
    val checklist: Checklist,
    val role: MemberRole?,
    val viaSuperAdmin: Boolean,
)

/**
 * THE place that decides who can do what to a checklist. Every endpoint that touches a
 * checklist, its members, invitations or items must go through [requireAccess] first.
 *
 * Rules:
 *  - Not a member (or the checklist doesn't exist)  -> 404, so IDs can't be probed.
 *  - Member, but role too low                       -> 403.
 *  - Checklist archived and the action is a write   -> 409 (archived lists are read-only).
 *  - Super admin                                    -> always allowed; writes get audited via [auditIfAdmin].
 */
@Service
class ChecklistAccessService(
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
    private val audit: AdminAuditService,
) {

    /**
     * @param minimum the lowest role allowed to perform the action.
     * @param allowArchived whether the action is allowed on an archived list. Defaults to true
     *        only for read-only (VIEWER-level) actions.
     */
    fun requireAccess(
        checklistId: UUID,
        user: User,
        minimum: MemberRole,
        allowArchived: Boolean = minimum == MemberRole.VIEWER,
    ): ChecklistAccess {
        val notFound = NotFoundException("Checklist not found")
        val checklist = checklists.findByIdOrNull(checklistId) ?: throw notFound
        val role = members.findByIdOrNull(ChecklistMemberId(checklistId, user.id!!))?.role

        val access = when {
            role != null && role.atLeast(minimum) -> ChecklistAccess(checklist, role, viaSuperAdmin = false)
            user.isSuperAdmin -> ChecklistAccess(checklist, role, viaSuperAdmin = true)
            role == null -> throw notFound
            else -> throw ForbiddenException("This needs the ${minimum.name.lowercase()} role or higher")
        }

        if (checklist.archivedAt != null && !allowArchived) {
            throw ConflictException("This checklist is archived. Unarchive it to make changes.")
        }
        return access
    }

    /** Call after a successful write. Logs it only when it was done through super admin powers. */
    fun auditIfAdmin(access: ChecklistAccess, user: User, action: String, details: String? = null) {
        if (access.viaSuperAdmin) {
            audit.record(user, action, "checklists", access.checklist.id, details)
        }
    }
}
