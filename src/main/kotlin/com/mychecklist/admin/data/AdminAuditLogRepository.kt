package com.mychecklist.admin.data

import com.mychecklist.admin.domain.AdminAuditLog
import org.springframework.data.jpa.repository.JpaRepository

interface AdminAuditLogRepository : JpaRepository<AdminAuditLog, Long>
