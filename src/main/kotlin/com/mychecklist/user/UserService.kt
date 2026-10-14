package com.mychecklist.user

import com.mychecklist.config.AppProperties
import com.mychecklist.common.ConflictException
import com.mychecklist.common.ForbiddenException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
            ?: throw ForbiddenException("Account has no email address")
        val emailVerified = jwt.getClaimAsBoolean("email_verified") == true

        users.insertIfAbsent(UUID.randomUUID(), uid, email)
        val user = users.findByFirebaseUid(uid)
            ?: throw ConflictException("This email is already linked to a different sign-in account")

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
        if (!user.isSuperAdmin) throw ForbiddenException("Super admin only")
    }
}
