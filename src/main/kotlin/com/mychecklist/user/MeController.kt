package com.mychecklist.user

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

@RestController
@RequestMapping("/api/me")
class MeController(private val userService: UserService) {

    /** The app calls this right after Firebase sign-in to create/fetch its user profile. */
    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): UserResponse =
        UserResponse.from(userService.syncFromToken(jwt))
}
