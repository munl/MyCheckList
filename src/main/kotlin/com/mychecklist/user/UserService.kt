package com.mychecklist.user

import com.mychecklist.config.AppProperties
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class UserService(
    private val users: UserRepository,
    private val props: AppProperties,
) {

    /**
     * Turns a verified Firebase token into our own User row: creates it on first
     * sign-in, and keeps name/photo/role in sync on later requests.
     */
    @Transactional
    fun syncFromToken(jwt: Jwt): User {
        val uid = jwt.subject
        val email = jwt.getClaimAsString("email")?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Account has no email address")
        val emailVerified = jwt.getClaimAsBoolean("email_verified") == true

        users.insertIfAbsent(UUID.randomUUID(), uid, email)
        val user = users.findByFirebaseUid(uid)
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Email $email is already linked to a different sign-in account",
            )

        user.email = email
        jwt.getClaimAsString("name")?.let { user.displayName = it }
        jwt.getClaimAsString("picture")?.let { user.photoUrl = it }
        // Only trust the email for admin purposes if Firebase has verified it.
        user.platformRole =
            if (emailVerified && email in props.superAdminEmailSet) PlatformRole.SUPER_ADMIN
            else PlatformRole.USER
        return user
    }

    fun requireSuperAdmin(user: User) {
        if (!user.isSuperAdmin) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Super admin only")
    }
}
