package com.mychecklist.occurrence.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

@Embeddable
data class OccurrenceItemAssigneeId(
    @Column(name = "occurrence_item_id", nullable = false)
    var occurrenceItemId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

@Entity
@Table(name = "occurrence_item_assignees")
class OccurrenceItemAssignee(
    @EmbeddedId
    var id: OccurrenceItemAssigneeId,

    /** Must match the item's checklist; the DB rejects assignees who aren't members. */
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,
)
