package com.mychecklist.occurrence.data

import com.mychecklist.occurrence.domain.OccurrenceItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

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
