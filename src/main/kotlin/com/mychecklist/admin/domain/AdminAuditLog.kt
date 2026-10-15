package com.mychecklist.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_audit_log")
class AdminAuditLog(
    @Column(name = "actor_user_id")
    var actorUserId: UUID?,

    @Column(nullable = false, length = 100)
    var action: String,

    @Column(name = "target_table", nullable = false, length = 100)
    var targetTable: String,

    @Column(name = "target_id")
    var targetId: UUID? = null,

    @Column
    var details: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}
