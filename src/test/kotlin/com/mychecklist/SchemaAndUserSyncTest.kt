package com.mychecklist

import com.mychecklist.checklist.Checklist
import com.mychecklist.checklist.ChecklistItem
import com.mychecklist.checklist.ChecklistItemRepository
import com.mychecklist.checklist.ChecklistMember
import com.mychecklist.checklist.ChecklistMemberId
import com.mychecklist.checklist.ChecklistMemberRepository
import com.mychecklist.checklist.ChecklistRepository
import com.mychecklist.checklist.ItemAssignee
import com.mychecklist.checklist.ItemAssigneeId
import com.mychecklist.checklist.ItemAssigneeRepository
import com.mychecklist.checklist.MemberRole
import com.mychecklist.user.PlatformRole
import com.mychecklist.user.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.Jwt
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    /** Starts a throwaway Postgres in Docker for the tests (Docker must be running). */
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:17-alpine")
}

/**
 * Starting the app at all proves the Flyway migration runs and the JPA entities match
 * the schema (ddl-auto=validate). The tests then exercise the key rules end to end.
 */
@SpringBootTest(properties = ["app.super-admin-emails=boss@example.com"])
@Import(TestcontainersConfig::class)
class SchemaAndUserSyncTest @Autowired constructor(
    private val userService: UserService,
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
    private val items: ChecklistItemRepository,
    private val assignees: ItemAssigneeRepository,
) {

    private fun token(uid: String, email: String, verified: Boolean = true): Jwt =
        Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject(uid)
            .claim("email", email)
            .claim("email_verified", verified)
            .claim("name", "Test $uid")
            .build()

    @Test
    fun `first sign-in creates the user and repeat sign-ins reuse it`() {
        val first = userService.syncFromToken(token("uid-a", "A@Example.com"))
        val again = userService.syncFromToken(token("uid-a", "a@example.com"))
        assertEquals(first.id, again.id)
        assertEquals("a@example.com", again.email)
        assertEquals(PlatformRole.USER, again.platformRole)
    }

    @Test
    fun `configured verified email becomes super admin, unverified does not`() {
        assertEquals(PlatformRole.SUPER_ADMIN, userService.syncFromToken(token("uid-boss", "boss@example.com")).platformRole)
        assertEquals(PlatformRole.USER, userService.syncFromToken(token("uid-boss", "boss@example.com", verified = false)).platformRole)
    }

    @Test
    fun `first person to complete an item wins`() {
        val owner = userService.syncFromToken(token("uid-o", "owner@example.com"))
        val friend = userService.syncFromToken(token("uid-f", "friend@example.com"))
        val list = checklists.save(Checklist(title = "Camping", createdBy = owner.id))
        members.save(ChecklistMember(ChecklistMemberId(list.id!!, owner.id!!), MemberRole.OWNER))
        members.save(ChecklistMember(ChecklistMemberId(list.id!!, friend.id!!), MemberRole.EDITOR))
        val item = items.save(ChecklistItem(checklistId = list.id!!, title = "Tent", position = 1000))
        assignees.save(ItemAssignee(ItemAssigneeId(item.id!!, owner.id!!), list.id!!))
        assignees.save(ItemAssignee(ItemAssigneeId(item.id!!, friend.id!!), list.id!!))

        assertEquals(1, items.completeIfOpen(item.id!!, friend.id!!, Instant.now()))
        assertEquals(0, items.completeIfOpen(item.id!!, owner.id!!, Instant.now()))
        assertEquals(friend.id, items.findById(item.id!!).get().completedBy)
        assertEquals(1, checklists.findActiveForUser(friend.id!!).size)
    }
}
