package com.mychecklist.member.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.mychecklist.checklist.domain.MemberRole
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/** Only exactly "EDITOR" or "VIEWER"; anything else (missing, null, lowercase, OWNER) is a 400. */
data class ChangeRoleRequest(
    @field:NotNull
    val role: MemberRole?,
)

data class MemberResponse(
    val userId: UUID,
    /** Null when Google didn't send a name. */
    val displayName: String?,
    val photoUrl: String?,
    /** Only for the owner and super admins. For anyone else the key is left out, not sent as null. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val email: String?,
    val role: MemberRole,
    val joinedAt: Instant?,
    val starBalance: Int,
    val currentStreak: Int,
    val longestStreak: Int,
)
