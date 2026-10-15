package com.mychecklist.checklist

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Query result: number of members in one checklist. */
interface ChecklistMemberCount {
    val checklistId: UUID
    val total: Long
}

/** Query result: number of template items in one checklist. */
interface ChecklistItemCount {
    val checklistId: UUID
    val total: Long
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
        select i.checklistId as checklistId, count(*) as total
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
}

interface ItemAssigneeRepository : JpaRepository<ItemAssignee, ItemAssigneeId> {
    fun findByIdItemId(itemId: UUID): List<ItemAssignee>

    /** Items assigned to one user in one checklist: the "separate by user" view. */
    fun findByChecklistIdAndIdUserId(checklistId: UUID, userId: UUID): List<ItemAssignee>
}

interface ChecklistScheduleRepository : JpaRepository<ChecklistSchedule, UUID>

interface ChecklistOccurrenceRepository : JpaRepository<ChecklistOccurrence, UUID> {
    fun findByChecklistIdAndOccurrenceDate(checklistId: UUID, occurrenceDate: LocalDate): ChecklistOccurrence?

    /** For the nightly job: open occurrences whose deadline has passed. */
    fun findByStatusAndDueAtLessThanEqual(status: OccurrenceStatus, now: Instant): List<ChecklistOccurrence>
}

interface OccurrenceItemRepository : JpaRepository<OccurrenceItem, UUID> {

    fun findByOccurrenceIdOrderByPositionAsc(occurrenceId: UUID): List<OccurrenceItem>

    /**
     * "Whoever gets to it first": only succeeds if nobody has completed the item yet.
     * Returns 1 if this caller completed it, 0 if someone else already had.
     * The caller must first check that the occurrence is still OPEN.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
        """
        update OccurrenceItem i
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

interface OccurrenceItemAssigneeRepository : JpaRepository<OccurrenceItemAssignee, OccurrenceItemAssigneeId> {
    fun findByIdOccurrenceItemId(occurrenceItemId: UUID): List<OccurrenceItemAssignee>
}

interface OccurrenceMemberResultRepository : JpaRepository<OccurrenceMemberResult, OccurrenceMemberResultId> {
    fun findByIdOccurrenceId(occurrenceId: UUID): List<OccurrenceMemberResult>
}
