package com.mychecklist.user.api

import com.mychecklist.user.domain.PlatformRole
import com.mychecklist.user.domain.User
import java.time.Instant
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val platformRole: PlatformRole,
    val createdAt: Instant?,
) {
    companion object {
        fun from(u: User) = UserResponse(u.id!!, u.email, u.displayName, u.photoUrl, u.platformRole, u.createdAt)
    }
}
