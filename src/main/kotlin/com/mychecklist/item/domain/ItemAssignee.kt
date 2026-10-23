package com.mychecklist.item.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class ItemAssigneeId(
    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

/** Default assignee of a template item; copied into each occurrence. */
@Entity
@Table(name = "item_assignees")
class ItemAssignee(
    @EmbeddedId
    var id: ItemAssigneeId,

    /**
     * Must match the item's checklist. The DB rejects people who were never members, but
     * not removed members (their row is kept), so code that assigns must check they're active.
     */
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "assigned_by")
    var assignedBy: UUID? = null,
) {
    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    var assignedAt: Instant? = null
}
