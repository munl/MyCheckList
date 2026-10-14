package com.mychecklist.admin

import com.mychecklist.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Records changes a super admin makes to data they don't own.
 * Runs inside the caller's transaction, so if the change rolls back, so does the log entry.
 */
@Service
class AdminAuditService(private val auditLog: AdminAuditLogRepository) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(actor: User, action: String, targetTable: String, targetId: UUID?, details: String? = null) {
        auditLog.save(AdminAuditLog(actor.id, action, targetTable, targetId, details))
    }
}
