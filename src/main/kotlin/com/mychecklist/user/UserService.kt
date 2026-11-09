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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class UserService(
    private val users: UserRepository,
    private val props: AppProperties,
    private val members: ChecklistMemberRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * When we last warned that someone in the setting is still on a list, so the warning (checked
     * on every request) is logged at most once a day per person. Kept in memory only: a restart
     * forgets it and warns once more. It should hold nobody, as admin emails are separate accounts.
     */
    private val lastStillOnListWarning = ConcurrentHashMap<UUID, Instant>()

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
     * runs the server (at most once a day each). Admin emails are meant to be separate accounts,
     * so this is a mistake case.
     */
    private fun stillOnAList(user: User): Boolean {
        val userId = user.id!!
        val onAList = members.existsByIdUserIdAndRemovedAtIsNull(userId)
        if (!onAList) {
            lastStillOnListWarning.remove(userId)
            return false
        }
        val now = clock.instant()
        var warnNow = false
        lastStillOnListWarning.compute(userId) { _, last ->
            if (last == null || !now.isBefore(last.plus(WARNING_INTERVAL))) {
                warnNow = true
                now
            } else {
                last
            }
        }
        if (warnNow) {
            log.warn(
                "{} is in APP_SUPER_ADMIN_EMAILS but is still on a list, so they stay USER until they leave every list",
                maskEmail(user.email),
            )
        }
        return true
    }

    companion object {
        private val WARNING_INTERVAL: Duration = Duration.ofDays(1)
    }

    fun requireSuperAdmin(user: User) {
        if (!user.isSuperAdmin) throw ForbiddenException("Super admin only")
    }
}
