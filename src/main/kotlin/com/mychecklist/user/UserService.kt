package com.mychecklist.user

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.maskEmail
import com.mychecklist.common.error.ForbiddenException
import com.mychecklist.config.AppProperties
import com.mychecklist.user.data.UserRepository
import com.mychecklist.user.domain.PlatformRole
import com.mychecklist.user.domain.User
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val users: UserRepository,
    private val props: AppProperties,
    private val members: ChecklistMemberRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val listedAsAdmin = emailVerified && email in props.superAdminEmailSet
        user.platformRole =
            if (listedAsAdmin && !stillOnAList(user)) PlatformRole.SUPER_ADMIN
            else PlatformRole.USER
        return user
    }

    /**
     * Super admins are never members of a list. Someone whose email is in the setting but who
     * is still on a list stays USER until they've left every list; the warning tells whoever
     * runs the server. Admin emails are meant to be separate accounts, so this is a mistake case.
     */
    private fun stillOnAList(user: User): Boolean {
        val onAList = members.existsByIdUserIdAndRemovedAtIsNull(user.id!!)
        if (onAList) {
            log.warn(
                "{} is in APP_SUPER_ADMIN_EMAILS but is still on a list, so they stay USER until they leave every list",
                maskEmail(user.email),
            )
        }
        return onAList
    }

    fun requireSuperAdmin(user: User) {
        if (!user.isSuperAdmin) throw ForbiddenException("Super admin only")
    }
}
