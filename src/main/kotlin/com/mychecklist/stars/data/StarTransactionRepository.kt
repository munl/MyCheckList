package com.mychecklist.stars.data

import com.mychecklist.stars.domain.StarTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface StarTransactionRepository : JpaRepository<StarTransaction, Long> {

    /** A member's history on one list, newest first. */
    fun findByChecklistIdAndUserIdOrderByCreatedAtDesc(checklistId: UUID, userId: UUID): List<StarTransaction>

    /** The true balance, recomputed from the ledger (used to check the cached balance). */
    @Query(
        """
        select coalesce(sum(t.amount), 0) from StarTransaction t
        where t.checklistId = :checklistId and t.userId = :userId and t.voidedAt is null
        """,
    )
    fun balanceOf(@Param("checklistId") checklistId: UUID, @Param("userId") userId: UUID): Long
}
