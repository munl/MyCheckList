package com.mychecklist.member

import com.jayway.jsonpath.JsonPath
import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.ForbiddenException
import com.mychecklist.support.SUPER_ADMIN_EMAIL
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import com.mychecklist.support.superAdmin
import com.mychecklist.user.UserService
import com.mychecklist.user.domain.PlatformRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PENDING_EMAIL = "pending@admins.test"
private const val PENDING_JOINS_EMAIL = "pending-joins@admins.test"
private const val LEFT_EMAIL = "left@admins.test"
private const val FORMER_EMAIL = "former@admins.test"

/**
 * Super admins are overseers with their own accounts, never members of a list (#20/#21).
 * See "Super admins are never on lists" in PROGRESS.md.
 *
 * Only someone whose role is already SUPER_ADMIN is blocked. An email in the setting whose
 * owner is still on a list stays USER (with a masked warning) and can keep using lists
 * normally until they've left every list (decided 2026-11-08).
 */
@SpringBootTest(
    properties = ["app.super-admin-emails=$SUPER_ADMIN_EMAIL,$PENDING_EMAIL,$PENDING_JOINS_EMAIL,$LEFT_EMAIL,$FORMER_EMAIL"],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
@ExtendWith(OutputCaptureExtension::class)
class SuperAdminNotOnListsTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val memberService: MemberService,
    private val members: ChecklistMemberRepository,
    private val userService: UserService,
    private val jdbc: JdbcTemplate,
) {

    private fun admin(email: String): RequestPostProcessor = signedIn("admin", email = email, uid = "uid-$email")

    /**
     * Makes the account behind [email] a plain USER on a list, as if they joined before their
     * email went into the setting: an unverified sign-in never counts for super admin.
     */
    private fun memberBeforePromotion(email: String, listId: UUID): UUID {
        val unverified = Jwt.withTokenValue("test").header("alg", "none")
            .subject("uid-$email").claim("email", email).claim("email_verified", false).build()
        val user = userService.syncFromToken(unverified)
        assertEquals(PlatformRole.USER, user.platformRole)
        memberService.addOrRestore(listId, user.id!!, MemberRole.EDITOR)
        return user.id!!
    }

    private fun platformRole(who: RequestPostProcessor): String =
        mvc.get("/api/me") { with(who) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
            .let { JsonPath.read(it, "$.platformRole") }

    private fun listsCreatedBy(userId: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM checklists WHERE created_by = ?", Int::class.java, userId)!!

    private fun markRemoved(listId: UUID, userId: UUID) {
        jdbc.update(
            "UPDATE checklist_members SET removed_at = now() WHERE checklist_id = ? AND user_id = ?",
            listId, userId,
        )
    }

    // --- a super admin can't create a list ------------------------------------

    @Test
    fun `a super admin can't create a list`() {
        mvc.post("/api/checklists") {
            with(superAdmin)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "Admin's list"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Super admins can't own or join lists") }
        }
        assertEquals(0, listsCreatedBy(checklists.userId(superAdmin)))
    }

    // --- a super admin can't be added to a list -------------------------------

    @Test
    fun `adding a super admin to a list is a 403 and adds no row`() {
        val id = checklists.createList(signedIn("owner"))
        val bossId = checklists.userId(superAdmin)

        val error = assertFailsWith<ForbiddenException> { memberService.addOrRestore(id, bossId, MemberRole.VIEWER) }

        assertEquals("Super admins can't own or join lists", error.message)
        assertFalse(members.existsById(ChecklistMemberId(id, bossId)))
    }

    @Test
    fun `a super admin's old removed row isn't restored`() {
        val id = checklists.createList(signedIn("owner"))
        val formerId = memberBeforePromotion(FORMER_EMAIL, id)
        markRemoved(id, formerId)
        assertEquals("SUPER_ADMIN", platformRole(admin(FORMER_EMAIL)))

        assertFailsWith<ForbiddenException> { memberService.addOrRestore(id, formerId, MemberRole.EDITOR) }

        assertNotNull(members.findById(ChecklistMemberId(id, formerId)).get().removedAt)
    }

    // --- an email in the setting, but still on a list ---------------------------

    @Test
    fun `someone still on a list stays USER, with a masked warning`(output: CapturedOutput) {
        val id = checklists.createList(signedIn("owner"))
        memberBeforePromotion(PENDING_EMAIL, id)

        assertEquals("USER", platformRole(admin(PENDING_EMAIL)))

        assertTrue(output.out.contains("p***@admins.test"), "the warning names the masked email")
        assertFalse(output.out.contains(PENDING_EMAIL), "the full email is never logged")
    }

    @Test
    fun `someone waiting to become a super admin can still create and join lists`() {
        val pending = admin(PENDING_JOINS_EMAIL)
        val first = checklists.createList(signedIn("owner"))
        val pendingId = memberBeforePromotion(PENDING_JOINS_EMAIL, first)
        assertEquals("USER", platformRole(pending))

        checklists.createList(pending, title = "Their own list")
        val second = checklists.createList(signedIn("other owner"))
        memberService.addOrRestore(second, pendingId, MemberRole.VIEWER)

        assertNotNull(members.findByIdAndRemovedAtIsNull(ChecklistMemberId(second, pendingId)))
    }

    @Test
    fun `once they're on no list, the next sign-in makes them super admin`() {
        val id = checklists.createList(signedIn("owner"))
        val leftId = memberBeforePromotion(LEFT_EMAIL, id)
        assertEquals("USER", platformRole(admin(LEFT_EMAIL)))

        markRemoved(id, leftId) // only a removed row is left

        assertEquals("SUPER_ADMIN", platformRole(admin(LEFT_EMAIL)))
    }

    // --- super admins still oversee every list ----------------------------------

    @Test
    fun `a super admin can still see any list without being a member`() {
        val id = checklists.createList(signedIn("owner"))

        mvc.get("/api/checklists/$id") { with(superAdmin) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { doesNotExist() }
        }
    }
}
