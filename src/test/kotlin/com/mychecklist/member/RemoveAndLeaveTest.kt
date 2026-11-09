package com.mychecklist.member

import com.jayway.jsonpath.JsonPath
import com.mychecklist.admin.data.AdminAuditLogRepository
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.SUPER_ADMIN_EMAIL
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import com.mychecklist.support.superAdmin
import com.mychecklist.user.UserService
import com.mychecklist.user.domain.PlatformRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LEAVES_LAST_EMAIL = "leaves-last@admins.test"
private const val LEAVES_ARCHIVED_EMAIL = "leaves-archived@admins.test"
private const val LEAVES_ONE_OF_TWO_EMAIL = "leaves-one-of-two@admins.test"

/**
 * `DELETE /api/checklists/{id}/members/{userId}` (owner, or a super admin) and
 * `POST /api/checklists/{id}/leave` (editor or viewer). Rules from #8, details agreed 2026-11-09:
 *  - both answer 204 with no body, and only set `removed_at`: the row keeps its stars and streaks
 *  - afterwards the person gets 404 on the list, and it's gone from their `GET /api/checklists`
 *  - remove: editor or viewer -> 403; outsider, removed caller or no such list -> 404 "Checklist not found";
 *    archived list -> 409; target never joined or already removed -> 404 "Member not found";
 *    target is the owner -> 409 "The owner can't be removed. Transfer ownership instead."
 *  - a super admin can remove on any list, audited as REMOVE_MEMBER with `userId=<member>, role=<role>`;
 *    the owner removing someone isn't audited
 *  - leave: works on an archived list; the owner -> 409 "The owner can't leave. Transfer ownership first.";
 *    outsider or someone who already left -> 404 "Checklist not found";
 *    a super admin (never a member) -> 404 "Member not found"; leaving is never audited
 *  - someone in `APP_SUPER_ADMIN_EMAILS` who leaves their last list (archived or not) is
 *    SUPER_ADMIN on their next request (#20/#21)
 * Order when several apply: caller (404, 403), archived (409), then target (404, 409).
 */
@SpringBootTest(
    properties = ["app.super-admin-emails=$SUPER_ADMIN_EMAIL,$LEAVES_LAST_EMAIL,$LEAVES_ARCHIVED_EMAIL,$LEAVES_ONE_OF_TWO_EMAIL"],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class RemoveAndLeaveTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val jdbc: JdbcTemplate,
    private val auditLog: AdminAuditLogRepository,
    private val memberService: MemberService,
    private val userService: UserService,
) {

    // --- helpers ------------------------------------------------------------

    private fun remove(listId: UUID, targetId: UUID, who: RequestPostProcessor) =
        mvc.delete("/api/checklists/$listId/members/$targetId") { with(who) }

    private fun remove(listId: UUID, target: RequestPostProcessor, who: RequestPostProcessor) =
        remove(listId, checklists.userId(target), who)

    private fun leave(listId: UUID, who: RequestPostProcessor) =
        mvc.post("/api/checklists/$listId/leave") { with(who) }

    private fun archive(listId: UUID, owner: RequestPostProcessor) {
        mvc.post("/api/checklists/$listId/archive") { with(owner) }.andExpect { status { isOk() } }
    }

    private fun markRemoved(listId: UUID, who: RequestPostProcessor) {
        val updated = jdbc.update(
            "UPDATE checklist_members SET removed_at = now() WHERE checklist_id = ? AND user_id = ?",
            listId, checklists.userId(who),
        )
        check(updated == 1) { "expected one member row to mark removed, got $updated" }
    }

    private fun giveStars(listId: UUID, who: RequestPostProcessor) {
        jdbc.update(
            "UPDATE checklist_members SET star_balance = 12, current_streak = 3, longest_streak = 7 " +
                "WHERE checklist_id = ? AND user_id = ?",
            listId, checklists.userId(who),
        )
    }

    /** The member's row as stored (null if there's no row). */
    private fun storedRow(listId: UUID, who: RequestPostProcessor): Map<String, Any?>? =
        jdbc.queryForList(
            "SELECT role, removed_at, star_balance, current_streak, longest_streak " +
                "FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            listId, checklists.userId(who),
        ).singleOrNull()

    private fun assertActive(listId: UUID, who: RequestPostProcessor, role: String) {
        val row = assertNotNull(storedRow(listId, who))
        assertNull(row["removed_at"], "should still be an active member")
        assertEquals(role, row["role"])
    }

    /** The row is still there, marked removed, with the stars and streaks from [giveStars]. */
    private fun assertSoftRemovedWithStars(listId: UUID, who: RequestPostProcessor) {
        val row = assertNotNull(storedRow(listId, who), "the member row must be kept")
        assertNotNull(row["removed_at"], "removed_at should be set")
        assertEquals(12, row["star_balance"])
        assertEquals(3, row["current_streak"])
        assertEquals(7, row["longest_streak"])
    }

    private fun myListIds(who: RequestPostProcessor, archived: Boolean = false): List<String> {
        val body = mvc.get("/api/checklists?archived=$archived") { with(who) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return JsonPath.read(body, "$[*].id")
    }

    private fun assertLostAccess(listId: UUID, who: RequestPostProcessor) {
        mvc.get("/api/checklists/$listId") { with(who) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    private fun auditRowsFor(listId: UUID) = auditLog.findAll().filter { it.targetId == listId }

    // =========================================================================
    // DELETE /api/checklists/{id}/members/{userId}
    // =========================================================================

    // --- happy path ---------------------------------------------------------

    @Test
    fun `the owner removes an editor, gets 204 with no body, and the editor loses access`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        remove(id, editor, owner).andExpect {
            status { isNoContent() }
            content { string("") }
        }

        assertLostAccess(id, editor)
        assertTrue(id.toString() !in myListIds(editor))
    }

    @Test
    fun `the owner removes a viewer, and the viewer loses access`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        remove(id, viewer, owner).andExpect { status { isNoContent() } }

        assertLostAccess(id, viewer)
    }

    @Test
    fun `a removed member's row is kept with their stars and streaks`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        giveStars(id, editor)

        remove(id, editor, owner).andExpect { status { isNoContent() } }

        assertSoftRemovedWithStars(id, editor)
    }

    @Test
    fun `a removed member is gone from the members list`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        remove(id, editor, owner).andExpect { status { isNoContent() } }

        val body = mvc.get("/api/checklists/$id/members") { with(owner) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(listOf(checklists.userId(owner).toString()), JsonPath.read<List<String>>(body, "$[*].userId"))
    }

    @Test
    fun `the owner removing someone isn't audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        remove(id, editor, owner).andExpect { status { isNoContent() } }

        assertTrue(auditRowsFor(id).isEmpty())
    }

    // --- who can call it ----------------------------------------------------

    @Test
    fun `an editor calling remove gets 403`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        remove(id, viewer, editor).andExpect { status { isForbidden() } }

        assertActive(id, viewer, "VIEWER")
    }

    @Test
    fun `a viewer calling remove gets 403`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        remove(id, editor, viewer).andExpect { status { isForbidden() } }

        assertActive(id, editor, "EDITOR")
    }

    @Test
    fun `an outsider calling remove gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        remove(id, editor, signedIn("outsider")).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
        assertActive(id, editor, "EDITOR")
    }

    @Test
    fun `a removed member calling remove gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val removed = signedIn("removed")
        val viewer = signedIn("viewer")
        checklists.addMember(id, removed, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        markRemoved(id, removed)

        remove(id, viewer, removed).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
        assertActive(id, viewer, "VIEWER")
    }

    @Test
    fun `removing on a list that doesn't exist gets 404`() {
        remove(UUID.randomUUID(), UUID.randomUUID(), signedIn("someone")).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    // --- who can be removed -------------------------------------------------

    @Test
    fun `removing the owner gets 409, and they stay owner`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        remove(id, owner, owner).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("The owner can't be removed. Transfer ownership instead.") }
        }
        assertActive(id, owner, "OWNER")
    }

    @Test
    fun `a super admin removing the owner gets 409, and it isn't audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        remove(id, owner, superAdmin).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("The owner can't be removed. Transfer ownership instead.") }
        }
        assertActive(id, owner, "OWNER")
        assertTrue(auditRowsFor(id).isEmpty())
    }

    @Test
    fun `removing someone who never joined gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        remove(id, signedIn("stranger"), owner).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
    }

    @Test
    fun `removing a user who doesn't exist at all gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        remove(id, UUID.randomUUID(), owner).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
    }

    @Test
    fun `removing someone who was already removed gets 404, and their removal time doesn't change`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val removed = signedIn("removed")
        checklists.addMember(id, removed, MemberRole.VIEWER)
        jdbc.update(
            "UPDATE checklist_members SET removed_at = '2026-10-01T00:00:00Z' WHERE checklist_id = ? AND user_id = ?",
            id, checklists.userId(removed),
        )

        remove(id, removed, owner).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
        val removedAt = jdbc.queryForObject(
            "SELECT removed_at = '2026-10-01T00:00:00Z' FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            Boolean::class.java, id, checklists.userId(removed),
        )
        assertEquals(true, removedAt)
    }

    // --- archived lists -----------------------------------------------------

    @Test
    fun `members can't be removed from an archived list (409)`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        archive(id, owner)

        remove(id, editor, owner).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("This checklist is archived. Unarchive it to make changes.") }
        }
        assertActive(id, editor, "EDITOR")
    }

    @Test
    fun `an editor calling remove on an archived list gets 403, not 409`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        archive(id, owner)

        remove(id, viewer, editor).andExpect { status { isForbidden() } }
    }

    @Test
    fun `on an archived list, removing someone who isn't a member gets 409, not 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        archive(id, owner)

        remove(id, signedIn("stranger"), owner).andExpect { status { isConflict() } }
    }

    // --- super admin --------------------------------------------------------

    @Test
    fun `a super admin can remove a member on any list, and it's audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        val viewerId = checklists.userId(viewer)

        remove(id, viewer, superAdmin).andExpect { status { isNoContent() } }

        assertLostAccess(id, viewer)
        val entry = auditRowsFor(id).single()
        assertEquals("REMOVE_MEMBER", entry.action)
        assertEquals("checklists", entry.targetTable)
        assertEquals(checklists.userId(superAdmin), entry.actorUserId)
        assertEquals("userId=$viewerId, role=VIEWER", entry.details)
    }

    // =========================================================================
    // POST /api/checklists/{id}/leave
    // =========================================================================

    @Test
    fun `an editor can leave, gets 204 with no body, then 404 on the list, and it's gone from their lists`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        assertTrue(id.toString() in myListIds(editor))

        leave(id, editor).andExpect {
            status { isNoContent() }
            content { string("") }
        }

        assertLostAccess(id, editor)
        assertTrue(id.toString() !in myListIds(editor))
    }

    @Test
    fun `a viewer can leave`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        leave(id, viewer).andExpect { status { isNoContent() } }

        assertLostAccess(id, viewer)
    }

    @Test
    fun `someone who leaves keeps their row, stars and streaks`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        giveStars(id, viewer)

        leave(id, viewer).andExpect { status { isNoContent() } }

        assertSoftRemovedWithStars(id, viewer)
    }

    @Test
    fun `leaving only removes the caller`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        leave(id, editor).andExpect { status { isNoContent() } }

        assertActive(id, owner, "OWNER")
        assertActive(id, viewer, "VIEWER")
    }

    @Test
    fun `the owner can't leave (409), and stays owner`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        leave(id, owner).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("The owner can't leave. Transfer ownership first.") }
        }
        assertActive(id, owner, "OWNER")
    }

    @Test
    fun `leaving works on an archived list, and it's gone from their archived lists`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        archive(id, owner)
        assertTrue(id.toString() in myListIds(editor, archived = true))

        leave(id, editor).andExpect { status { isNoContent() } }

        assertLostAccess(id, editor)
        assertTrue(id.toString() !in myListIds(editor, archived = true))
    }

    @Test
    fun `the owner can't leave an archived list either (409)`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        archive(id, owner)

        leave(id, owner).andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("The owner can't leave. Transfer ownership first.") }
        }
        assertActive(id, owner, "OWNER")
    }

    @Test
    fun `an outsider leaving gets 404`() {
        val id = checklists.createList(signedIn("owner"))

        leave(id, signedIn("outsider")).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `leaving twice gets 404 the second time`() {
        val id = checklists.createList(signedIn("owner"))
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        leave(id, viewer).andExpect { status { isNoContent() } }
        leave(id, viewer).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `leaving a list that doesn't exist gets 404`() {
        leave(UUID.randomUUID(), signedIn("someone")).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `a super admin calling leave gets 404 Member not found, and it isn't audited`() {
        val id = checklists.createList(signedIn("owner"))

        leave(id, superAdmin).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
        assertTrue(auditRowsFor(id).isEmpty())
    }

    @Test
    fun `leaving isn't audited`() {
        val id = checklists.createList(signedIn("owner"))
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        leave(id, editor).andExpect { status { isNoContent() } }

        assertTrue(auditRowsFor(id).isEmpty())
    }

    // --- leaving your last list as someone waiting to be a super admin (#20/#21) --

    private fun admin(email: String): RequestPostProcessor = signedIn("admin", email = email, uid = "uid-$email")

    /**
     * Makes the account behind [email] a plain USER on a list, as if they joined before their
     * email went into the setting: an unverified sign-in never counts for super admin.
     */
    private fun memberBeforePromotion(email: String, listId: UUID) {
        val unverified = Jwt.withTokenValue("test").header("alg", "none")
            .subject("uid-$email").claim("email", email).claim("email_verified", false).build()
        val user = userService.syncFromToken(unverified)
        assertEquals(PlatformRole.USER, user.platformRole)
        memberService.addOrRestore(listId, user.id!!, MemberRole.EDITOR)
    }

    private fun platformRole(who: RequestPostProcessor): String =
        mvc.get("/api/me") { with(who) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
            .let { JsonPath.read(it, "$.platformRole") }

    @Test
    fun `someone in the super admin setting who leaves their last list is SUPER_ADMIN on their next request`() {
        val id = checklists.createList(signedIn("owner"))
        memberBeforePromotion(LEAVES_LAST_EMAIL, id)
        val pending = admin(LEAVES_LAST_EMAIL)
        assertEquals("USER", platformRole(pending))

        leave(id, pending).andExpect { status { isNoContent() } }

        assertEquals("SUPER_ADMIN", platformRole(pending))
    }

    @Test
    fun `the same goes when their last list is archived`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        memberBeforePromotion(LEAVES_ARCHIVED_EMAIL, id)
        archive(id, owner)
        val pending = admin(LEAVES_ARCHIVED_EMAIL)
        assertEquals("USER", platformRole(pending))

        leave(id, pending).andExpect { status { isNoContent() } }

        assertEquals("SUPER_ADMIN", platformRole(pending))
    }

    @Test
    fun `leaving one list while still on another keeps them USER`() {
        val first = checklists.createList(signedIn("owner"))
        val second = checklists.createList(signedIn("other owner"))
        memberBeforePromotion(LEAVES_ONE_OF_TWO_EMAIL, first)
        memberBeforePromotion(LEAVES_ONE_OF_TWO_EMAIL, second)
        val pending = admin(LEAVES_ONE_OF_TWO_EMAIL)

        leave(first, pending).andExpect { status { isNoContent() } }

        assertEquals("USER", platformRole(pending))
    }
}
