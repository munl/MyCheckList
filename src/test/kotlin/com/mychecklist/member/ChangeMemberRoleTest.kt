package com.mychecklist.member

import com.jayway.jsonpath.JsonPath
import com.mychecklist.admin.data.AdminAuditLogRepository
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.SUPER_ADMIN_EMAIL
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import com.mychecklist.support.superAdmin
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PATCH /api/checklists/{id}/members/{userId}` with `{"role": "EDITOR" | "VIEWER"}` (OWNER only,
 * or a super admin). Rules agreed 2026-11-07 (#4):
 *  - 200 with the updated member, in the same shape as a members-list entry (with `email`)
 *  - setting the role they already have: 200, nothing changes, and a super admin isn't audited
 *  - `OWNER`, or a missing, null, unknown or lowercase role -> 400 (ownership only moves by transfer)
 *  - editor or viewer -> 403; outsider, removed caller or no such list -> 404 "Checklist not found"
 *  - target never joined or removed -> 404 "Member not found"; target is the owner -> 409
 *  - archived list -> 409
 *  - a super admin can do it on any list; each real change is audited as CHANGE_MEMBER_ROLE
 *    with details `userId=<member>, from=<old>, to=<new>`
 * Order when several apply: body (400), caller (404, 403), archived (409), then target (404, 409).
 */
@SpringBootTest(properties = ["app.super-admin-emails=$SUPER_ADMIN_EMAIL"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class ChangeMemberRoleTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val jdbc: JdbcTemplate,
    private val auditLog: AdminAuditLogRepository,
) {

    // --- helpers ------------------------------------------------------------

    private fun changeRole(listId: UUID, targetId: UUID, who: RequestPostProcessor, body: String) =
        mvc.patch("/api/checklists/$listId/members/$targetId") {
            with(who)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun changeRole(listId: UUID, target: RequestPostProcessor, who: RequestPostProcessor, role: String) =
        changeRole(listId, checklists.userId(target), who, """{"role": "$role"}""")

    private fun markRemoved(listId: UUID, who: RequestPostProcessor) {
        val updated = jdbc.update(
            "UPDATE checklist_members SET removed_at = now() WHERE checklist_id = ? AND user_id = ?",
            listId, checklists.userId(who),
        )
        check(updated == 1) { "expected one member row to mark removed, got $updated" }
    }

    /** The member's role as stored, read straight from the database (null if there's no row). */
    private fun storedRole(listId: UUID, who: RequestPostProcessor): String? =
        jdbc.queryForList(
            "SELECT role FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            String::class.java, listId, checklists.userId(who),
        ).singleOrNull()

    private fun auditRowsFor(listId: UUID) = auditLog.findAll().filter { it.targetId == listId }

    private fun archive(listId: UUID, owner: RequestPostProcessor) {
        mvc.post("/api/checklists/$listId/archive") { with(owner) }.andExpect { status { isOk() } }
    }

    // --- happy path ---------------------------------------------------------

    @Test
    fun `the owner can make an editor a viewer, and gets the updated member back`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val samEmail = "sam-${UUID.randomUUID()}@example.com"
        val sam = signedIn("Sam", email = samEmail, picture = "https://example.com/sam.png")
        checklists.addMember(id, sam, MemberRole.EDITOR)
        jdbc.update(
            "UPDATE checklist_members SET star_balance = 12, current_streak = 3, longest_streak = 7 " +
                "WHERE checklist_id = ? AND user_id = ?",
            id, checklists.userId(sam),
        )

        changeRole(id, sam, owner, "VIEWER").andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(checklists.userId(sam).toString()) }
            jsonPath("$.displayName") { value("Sam") }
            jsonPath("$.photoUrl") { value("https://example.com/sam.png") }
            jsonPath("$.email") { value(samEmail) }
            jsonPath("$.role") { value("VIEWER") }
            jsonPath("$.joinedAt") { exists() }
            jsonPath("$.starBalance") { value(12) }
            jsonPath("$.currentStreak") { value(3) }
            jsonPath("$.longestStreak") { value(7) }
        }
        assertEquals("VIEWER", storedRole(id, sam))
    }

    @Test
    fun `the owner can make a viewer an editor`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, viewer, owner, "EDITOR").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("EDITOR") }
        }
        assertEquals("EDITOR", storedRole(id, viewer))
    }

    @Test
    fun `the new role shows in the members list`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        changeRole(id, editor, owner, "VIEWER").andExpect { status { isOk() } }

        val body = mvc.get("/api/checklists/$id/members") { with(owner) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val roles: List<String> =
            JsonPath.read(body, "$[?(@.userId == '${checklists.userId(editor)}')].role")
        assertEquals(listOf("VIEWER"), roles)
    }

    @Test
    fun `setting the role a member already has changes nothing`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        jdbc.update(
            "UPDATE checklist_members SET joined_at = '2026-10-01T00:00:00Z' WHERE checklist_id = ? AND user_id = ?",
            id, checklists.userId(editor),
        )

        changeRole(id, editor, owner, "EDITOR").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("EDITOR") }
            jsonPath("$.joinedAt") { value("2026-10-01T00:00:00Z") }
        }
        assertEquals("EDITOR", storedRole(id, editor))
    }

    @Test
    fun `the owner changing a role isn't audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        changeRole(id, editor, owner, "VIEWER").andExpect { status { isOk() } }

        assertTrue(auditRowsFor(id).isEmpty())
    }

    // --- bad body -----------------------------------------------------------

    @Test
    fun `asking for OWNER gets 400, and the role doesn't change`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        changeRole(id, editor, owner, "OWNER").andExpect { status { isBadRequest() } }

        assertEquals("EDITOR", storedRole(id, editor))
        assertEquals("OWNER", storedRole(id, owner))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"role": null}""", """{"role": "ADMIN"}""", """{"role": "editor"}""", """{"role": ""}"""])
    fun `a missing, null, unknown or lowercase role gets 400`(body: String) {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, checklists.userId(viewer), owner, body).andExpect { status { isBadRequest() } }

        assertEquals("VIEWER", storedRole(id, viewer))
    }

    @Test
    fun `a bad body is checked before permissions, so a viewer asking for OWNER gets 400`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, owner, viewer, "OWNER").andExpect { status { isBadRequest() } }
    }

    // --- who can call it ----------------------------------------------------

    @Test
    fun `an editor gets 403`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, viewer, editor, "EDITOR").andExpect { status { isForbidden() } }

        assertEquals("VIEWER", storedRole(id, viewer))
    }

    @Test
    fun `a viewer gets 403`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, editor, viewer, "VIEWER").andExpect { status { isForbidden() } }

        assertEquals("EDITOR", storedRole(id, editor))
    }

    @Test
    fun `an outsider gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        changeRole(id, editor, signedIn("outsider"), "VIEWER").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
        assertEquals("EDITOR", storedRole(id, editor))
    }

    @Test
    fun `a removed member calling it gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val removed = signedIn("removed")
        val viewer = signedIn("viewer")
        checklists.addMember(id, removed, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        markRemoved(id, removed)

        changeRole(id, viewer, removed, "EDITOR").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `a list that doesn't exist gets 404`() {
        changeRole(UUID.randomUUID(), UUID.randomUUID(), signedIn("someone"), """{"role": "VIEWER"}""").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    // --- who it can be done to ----------------------------------------------

    @Test
    fun `a target who never joined the list gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        changeRole(id, signedIn("stranger"), owner, "VIEWER").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
    }

    @Test
    fun `a target who doesn't exist at all gets 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        changeRole(id, UUID.randomUUID(), owner, """{"role": "VIEWER"}""").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
    }

    @Test
    fun `a removed target gets 404 and stays removed`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val removed = signedIn("removed")
        checklists.addMember(id, removed, MemberRole.VIEWER)
        markRemoved(id, removed)

        changeRole(id, removed, owner, "EDITOR").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Member not found") }
        }
        assertEquals("VIEWER", storedRole(id, removed))
        val stillRemoved = jdbc.queryForObject(
            "SELECT removed_at IS NOT NULL FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            Boolean::class.java, id, checklists.userId(removed),
        )
        assertEquals(true, stillRemoved)
    }

    @Test
    fun `the owner can't change their own role (409)`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        changeRole(id, owner, owner, "EDITOR").andExpect { status { isConflict() } }

        assertEquals("OWNER", storedRole(id, owner))
    }

    @Test
    fun `a super admin can't change the owner's role (409)`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        changeRole(id, owner, superAdmin, "VIEWER").andExpect { status { isConflict() } }

        assertEquals("OWNER", storedRole(id, owner))
        assertTrue(auditRowsFor(id).isEmpty())
    }

    // --- archived lists -----------------------------------------------------

    @Test
    fun `roles can't be changed on an archived list (409)`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        archive(id, owner)

        changeRole(id, editor, owner, "VIEWER").andExpect { status { isConflict() } }

        assertEquals("EDITOR", storedRole(id, editor))
    }

    @Test
    fun `an editor on an archived list gets 403, not 409`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        val viewer = signedIn("viewer")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        archive(id, owner)

        changeRole(id, viewer, editor, "EDITOR").andExpect { status { isForbidden() } }
    }

    @Test
    fun `on an archived list, a target who isn't a member gets 409, not 404`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        archive(id, owner)

        changeRole(id, signedIn("stranger"), owner, "VIEWER").andExpect { status { isConflict() } }
    }

    // --- super admin --------------------------------------------------------

    @Test
    fun `a super admin can change a role on any list, and it's audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        val editorId = checklists.userId(editor)

        changeRole(id, editor, superAdmin, "VIEWER").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("VIEWER") }
            jsonPath("$.email") { exists() }
        }
        assertEquals("VIEWER", storedRole(id, editor))

        val entry = auditRowsFor(id).single()
        assertEquals("CHANGE_MEMBER_ROLE", entry.action)
        assertEquals("checklists", entry.targetTable)
        assertEquals(checklists.userId(superAdmin), entry.actorUserId)
        assertEquals("userId=$editorId, from=EDITOR, to=VIEWER", entry.details)
    }

    @Test
    fun `a super admin setting the role a member already has isn't audited`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        changeRole(id, viewer, superAdmin, "VIEWER").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("VIEWER") }
        }

        assertTrue(auditRowsFor(id).isEmpty())
    }
}
