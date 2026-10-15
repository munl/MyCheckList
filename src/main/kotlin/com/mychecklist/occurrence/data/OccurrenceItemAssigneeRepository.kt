package com.mychecklist.occurrence.data

import com.mychecklist.occurrence.domain.OccurrenceItemAssignee
import com.mychecklist.occurrence.domain.OccurrenceItemAssigneeId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OccurrenceItemAssigneeRepository : JpaRepository<OccurrenceItemAssignee, OccurrenceItemAssigneeId> {
    fun findByIdOccurrenceItemId(occurrenceItemId: UUID): List<OccurrenceItemAssignee>
}
