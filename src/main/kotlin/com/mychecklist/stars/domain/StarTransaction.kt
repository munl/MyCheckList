package com.mychecklist.stars.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * The star ledger. Every star earned or spent is one row; balances are sums of rows
 * that aren't voided. Rows are never edited, except that an award is voided (not deleted)
 * if an item is unticked before its occurrence closes.
 */
@Entity
@Table(name = "star_transactions")
class StarTransaction(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    /** Positive = earned/refunded, negative = spent/removed. Never 0. */
    @Column(nullable = false)
    var amount: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: StarTransactionType,

    @Column(name = "occurrence_id")
    var occurrenceId: UUID? = null,

    @Column(name = "redemption_id")
    var redemptionId: UUID? = null,

    @Column
    var reason: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null
}
