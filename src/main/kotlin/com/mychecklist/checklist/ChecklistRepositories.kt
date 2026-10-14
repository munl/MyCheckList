package com.mychecklist.checklist

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ChecklistRepository : JpaRepository<Checklist, UUID> {

    /** Checklists the user is a member of (any role), excluding archived ones. */
    @Query(
        """
        select c from Checklist c
        where c.archivedAt is null
          and exists (select 1 from ChecklistMember m where m.id.checklistId = c.id and m.id.userId = :userId)
        order by c.updatedAt desc
        """,
    )
    fun findActiveForUser(@Param("userId") userId: UUID): List<Checklist>
}

interface ChecklistMemberRepository : JpaRepository<ChecklistMember, ChecklistMemberId> {
    fun findByIdChecklistId(checklistId: UUID): List<ChecklistMember>
}

interface ChecklistInvitationRepository : JpaRepository<ChecklistInvitation, UUID> {
    /** The invite inbox. */
    fun findByInviteeEmailAndStatusOrderByCreatedAtDesc(
        inviteeEmail: String,
        status: InvitationStatus,
    ): List<ChecklistInvitation>
}

interface ChecklistItemRepository : JpaRepository<ChecklistItem, UUID> {

    fun findByChecklistIdOrderByPositionAsc(checklistId: UUID): List<ChecklistItem>

    /**
     * "Whoever gets to it first": only succeeds if nobody has completed the item yet.
     * Returns 1 if this caller completed it, 0 if someone else already had.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        """
        update ChecklistItem i
        set i.completedAt = :now, i.completedBy = :userId, i.version = i.version + 1
        where i.id = :itemId and i.completedAt is null
        """,
    )
    fun completeIfOpen(
        @Param("itemId") itemId: UUID,
        @Param("userId") userId: UUID,
        @Param("now") now: Instant,
    ): Int
}

interface ItemAssigneeRepository : JpaRepository<ItemAssignee, ItemAssigneeId> {
    fun findByIdItemId(itemId: UUID): List<ItemAssignee>

    /** Items assigned to one user in one checklist: the "separate by user" view. */
    fun findByChecklistIdAndIdUserId(checklistId: UUID, userId: UUID): List<ItemAssignee>
}
