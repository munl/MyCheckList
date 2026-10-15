package com.mychecklist.schedule.data

import com.mychecklist.schedule.domain.ChecklistSchedule
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChecklistScheduleRepository : JpaRepository<ChecklistSchedule, UUID>
