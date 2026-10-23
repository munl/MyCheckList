package com.mychecklist.checklist.data

import com.mychecklist.checklist.domain.Checklist
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Query result: number of members (not removed) in one checklist. */
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

    /** Checklists the user is a member of (any role, not removed), excluding archived ones. */
    @Query(
        """
        select c from Checklist c
        where c.archivedAt is null
          and exists (
              select 1 from ChecklistMember m
              where m.id.checklistId = c.id and m.id.userId = :userId and m.removedAt is null
          )
        order by c.updatedAt desc
        """,
    )
    fun findActiveForUser(@Param("userId") userId: UUID): List<Checklist>

    /** Archived checklists the user is a member of. */
    @Query(
        """
        select c from Checklist c
        where c.archivedAt is not null
          and exists (
              select 1 from ChecklistMember m
              where m.id.checklistId = c.id and m.id.userId = :userId and m.removedAt is null
          )
        order by c.archivedAt desc
        """,
    )
    fun findArchivedForUser(@Param("userId") userId: UUID): List<Checklist>

    @Query(
        """
        select m.id.checklistId as checklistId, count(*) as total
        from ChecklistMember m
        where m.id.checklistId in :ids and m.removedAt is null
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
