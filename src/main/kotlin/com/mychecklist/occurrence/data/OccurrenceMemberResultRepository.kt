package com.mychecklist.occurrence.data

import com.mychecklist.occurrence.domain.OccurrenceMemberResult
import com.mychecklist.occurrence.domain.OccurrenceMemberResultId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OccurrenceMemberResultRepository : JpaRepository<OccurrenceMemberResult, OccurrenceMemberResultId> {
    fun findByIdOccurrenceId(occurrenceId: UUID): List<OccurrenceMemberResult>
}
