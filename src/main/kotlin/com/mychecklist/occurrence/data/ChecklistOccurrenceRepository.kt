package com.mychecklist.occurrence.data

import com.mychecklist.occurrence.domain.ChecklistOccurrence
import com.mychecklist.occurrence.domain.OccurrenceStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ChecklistOccurrenceRepository : JpaRepository<ChecklistOccurrence, UUID> {
    fun findByChecklistIdAndOccurrenceDate(checklistId: UUID, occurrenceDate: LocalDate): ChecklistOccurrence?

    /** For the nightly job: open occurrences whose deadline has passed. */
    fun findByStatusAndDueAtLessThanEqual(status: OccurrenceStatus, now: Instant): List<ChecklistOccurrence>
}
