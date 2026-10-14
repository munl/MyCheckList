package com.mychecklist.checklist

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateChecklistRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,

    @field:Size(max = 5000)
    val description: String? = null,
)

/**
 * Partial update: fields left out (or null) are not changed.
 * Send an empty string for `description` to clear it.
 */
data class UpdateChecklistRequest(
    @field:Size(min = 1, max = 200)
    val title: String? = null,

    @field:Size(max = 5000)
    val description: String? = null,
)

data class ChecklistResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    /** The caller's role in this list. Null only for a super admin who isn't a member. */
    val myRole: MemberRole?,
    val archived: Boolean,
    val archivedAt: Instant?,
    val memberCount: Long,
    val itemCount: Long,
    val completedItemCount: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
