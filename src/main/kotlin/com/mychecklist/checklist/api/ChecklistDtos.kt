package com.mychecklist.checklist.api

import com.mychecklist.checklist.domain.MemberRole
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

    /** IANA time zone, e.g. "America/Vancouver". Send the phone's zone. Defaults to UTC. */
    @field:Size(max = 64)
    val timeZone: String? = null,
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

    @field:Size(max = 64)
    val timeZone: String? = null,
)

data class ChecklistResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    /** The caller's role in this list. Null only for a super admin who isn't a member. */
    val myRole: MemberRole?,
    val archived: Boolean,
    val archivedAt: Instant?,
    val timeZone: String,
    val memberCount: Long,
    /** Number of items in the list's template. */
    val itemCount: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
