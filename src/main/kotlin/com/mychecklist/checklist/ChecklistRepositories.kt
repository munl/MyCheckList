package com.mychecklist.checklist

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Query result: number of members in one checklist. */
interface ChecklistMemberCount {
    val checklistId: UUID
    val total: Long
}

/** Query result: total and completed items in one checklist. */
interface ChecklistItemCount {
    val checklistId: UUID
    val total: Long
    val completed: Long
}

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

    /** Archived checklists the user is a member of. */
    @Query(
        """
        select c from Checklist c
        where c.archivedAt is not null
          and exists (select 1 from ChecklistMember m where m.id.checklistId = c.id and m.id.userId = :userId)
        order by c.archivedAt desc
        """,
    )
    fun findArchivedForUser(@Param("userId") userId: UUID): List<Checklist>

    @Query(
        """
        select m.id.checklistId as checklistId, count(*) as total
        from ChecklistMember m
        where m.id.checklistId in :ids
        group by m.id.checklistId
        """,
    )
    fun countMembers(@Param("ids") ids: Collection<UUID>): List<ChecklistMemberCount>

    @Query(
        """
        select i.checklistId as checklistId, count(*) as total, count(i.completedAt) as completed
        from ChecklistItem i
        where i.checklistId in :ids
        group by i.checklistId
        """,
    )
    fun countItems(@Param("ids") ids: Collection<UUID>): List<ChecklistItemCount>
}

interface ChecklistMemberRepository : JpaRepository<ChecklistMember, ChecklistMemberId> {
    fun findByIdChecklistId(checklistId: UUID): List<ChecklistMember>

    /** All of one user's memberships (to show their role on each list). */
    fun findByIdUserId(userId: UUID): List<ChecklistMember>

    fun countByIdUserIdAndRole(userId: UUID, role: MemberRole): Long
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
