package com.mychecklist.checklist

import com.mychecklist.common.BadRequestException
import com.mychecklist.common.ConflictException
import com.mychecklist.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ChecklistService(
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
    private val access: ChecklistAccessService,
) {

    companion object {
        /** Abuse guard: how many lists one person can own. */
        const val MAX_OWNED_CHECKLISTS = 200
    }

    @Transactional
    fun create(user: User, request: CreateChecklistRequest): ChecklistResponse {
        if (members.countByIdUserIdAndRole(user.id!!, MemberRole.OWNER) >= MAX_OWNED_CHECKLISTS) {
            throw ConflictException("You can own at most $MAX_OWNED_CHECKLISTS checklists")
        }
        val checklist = checklists.saveAndFlush(
            Checklist(
                title = request.title.trim(),
                description = request.description.normalized(),
                createdBy = user.id,
            ),
        )
        members.save(ChecklistMember(ChecklistMemberId(checklist.id!!, user.id!!), MemberRole.OWNER))
        return toResponses(listOf(checklist), mapOf(checklist.id!! to MemberRole.OWNER)).single()
    }

    @Transactional(readOnly = true)
    fun listMine(user: User, archived: Boolean): List<ChecklistResponse> {
        val lists = if (archived) checklists.findArchivedForUser(user.id!!) else checklists.findActiveForUser(user.id!!)
        val roles = members.findByIdUserId(user.id!!).associate { it.id.checklistId to it.role }
        return toResponses(lists, roles)
    }

    @Transactional(readOnly = true)
    fun get(user: User, checklistId: UUID): ChecklistResponse {
        val granted = access.requireAccess(checklistId, user, MemberRole.VIEWER)
        return toResponse(granted)
    }

    @Transactional
    fun update(user: User, checklistId: UUID, request: UpdateChecklistRequest): ChecklistResponse {
        val granted = access.requireAccess(checklistId, user, MemberRole.EDITOR)
        val checklist = granted.checklist
        request.title?.let {
            if (it.isBlank()) throw BadRequestException("Title can't be blank")
            checklist.title = it.trim()
        }
        if (request.description != null) checklist.description = request.description.normalized()
        checklists.saveAndFlush(checklist)
        access.auditIfAdmin(granted, user, "UPDATE_CHECKLIST")
        return toResponse(granted)
    }

    @Transactional
    fun archive(user: User, checklistId: UUID): ChecklistResponse {
        val granted = access.requireAccess(checklistId, user, MemberRole.OWNER, allowArchived = true)
        if (granted.checklist.archivedAt == null) {
            granted.checklist.archivedAt = Instant.now()
            checklists.saveAndFlush(granted.checklist)
            access.auditIfAdmin(granted, user, "ARCHIVE_CHECKLIST")
        }
        return toResponse(granted)
    }

    @Transactional
    fun unarchive(user: User, checklistId: UUID): ChecklistResponse {
        val granted = access.requireAccess(checklistId, user, MemberRole.OWNER, allowArchived = true)
        if (granted.checklist.archivedAt != null) {
            granted.checklist.archivedAt = null
            checklists.saveAndFlush(granted.checklist)
            access.auditIfAdmin(granted, user, "UNARCHIVE_CHECKLIST")
        }
        return toResponse(granted)
    }

    /** Permanently deletes the list and everything in it (members, items, invitations). */
    @Transactional
    fun delete(user: User, checklistId: UUID, confirm: Boolean) {
        val granted = access.requireAccess(checklistId, user, MemberRole.OWNER, allowArchived = true)
        if (!confirm) throw BadRequestException("Deleting is permanent. Repeat the request with ?confirm=true")
        access.auditIfAdmin(granted, user, "DELETE_CHECKLIST", "title=${granted.checklist.title}")
        checklists.delete(granted.checklist)
    }

    // --- mapping ------------------------------------------------------------

    private fun toResponse(granted: ChecklistAccess): ChecklistResponse {
        val roles = granted.role?.let { mapOf(granted.checklist.id!! to it) } ?: emptyMap()
        return toResponses(listOf(granted.checklist), roles).single()
    }

    /** Builds responses for several lists with 2 count queries in total (not 2 per list). */
    private fun toResponses(lists: List<Checklist>, roles: Map<UUID, MemberRole>): List<ChecklistResponse> {
        if (lists.isEmpty()) return emptyList()
        val ids = lists.map { it.id!! }
        val memberCounts = checklists.countMembers(ids).associate { it.checklistId to it.total }
        val itemCounts = checklists.countItems(ids).associateBy { it.checklistId }
        return lists.map { c ->
            val id = c.id!!
            ChecklistResponse(
                id = id,
                title = c.title,
                description = c.description,
                myRole = roles[id],
                archived = c.archivedAt != null,
                archivedAt = c.archivedAt,
                memberCount = memberCounts[id] ?: 0,
                itemCount = itemCounts[id]?.total ?: 0,
                completedItemCount = itemCounts[id]?.completed ?: 0,
                createdAt = c.createdAt,
                updatedAt = c.updatedAt,
            )
        }
    }

    /** Trims text; blank becomes null. */
    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
