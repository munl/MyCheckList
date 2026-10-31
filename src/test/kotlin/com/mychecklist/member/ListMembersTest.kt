package com.mychecklist.member

import com.jayway.jsonpath.JsonPath
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.SUPER_ADMIN_EMAIL
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import com.mychecklist.support.superAdmin
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/checklists/{id}/members` (VIEWER or higher). Rules agreed 2026-10-30 (#6):
 *  - active members only, the owner first, then everyone else by join date (earliest first),
 *    ties by userId
 *  - each has userId, displayName, photoUrl, role, joinedAt, starBalance, currentStreak, longestStreak;
 *    a missing name or photo is sent as null
 *  - emails only for the owner and super admin; editors and viewers get no `email` key at all
 *  - works on archived lists; outsiders and removed members get 404
 *  - a super admin reading it isn't audited (only writes are)
 *
 * Super admins are never members of a list, so there's no "super admin who is also a member" case.
 */
@SpringBootTest(properties = ["app.super-admin-emails=$SUPER_ADMIN_EMAIL"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class ListMembersTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val jdbc: JdbcTemplate,
) {

    // --- helpers ------------------------------------------------------------

    private fun markRemoved(listId: UUID, who: RequestPostProcessor) {
        val updated = jdbc.update(
            "UPDATE checklist_members SET removed_at = now() WHERE checklist_id = ? AND user_id = ?",
            listId, checklists.userId(who),
        )
        check(updated == 1) { "expected one member row to mark removed, got $updated" }
    }

    /** Sets a member's join date by hand, so the order doesn't depend on test timing. */
    private fun setJoinedAt(listId: UUID, who: RequestPostProcessor, joinedAt: String) {
        jdbc.update(
            "UPDATE checklist_members SET joined_at = ?::timestamptz WHERE checklist_id = ? AND user_id = ?",
            joinedAt, listId, checklists.userId(who),
        )
    }

    /** The caller's view of the members list, one map per member. Expects 200. */
    private fun membersAs(who: RequestPostProcessor, listId: UUID): List<Map<String, Any?>> {
        val body = mvc.get("/api/checklists/$listId/members") { with(who) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return JsonPath.read(body, "$")
    }

    private fun List<Map<String, Any?>>.forUser(who: RequestPostProcessor): Map<String, Any?> =
        single { it["userId"] == checklists.userId(who).toString() }

    // --- what's returned ----------------------------------------------------

    @Test
    fun `each member comes back with their profile, role, join date, stars and streaks`() {
        val owner = signedIn("Mum", picture = "https://example.com/mum.png")
        val id = checklists.createList(owner)
        val sam = signedIn("Sam")
        checklists.addMember(id, sam, MemberRole.VIEWER)
        jdbc.update(
            "UPDATE checklist_members SET star_balance = 12, current_streak = 3, longest_streak = 7 " +
                "WHERE checklist_id = ? AND user_id = ?",
            id, checklists.userId(sam),
        )

        val members = membersAs(owner, id)

        assertEquals(2, members.size)
        val mum = members.forUser(owner)
        assertEquals("Mum", mum["displayName"])
        assertEquals("https://example.com/mum.png", mum["photoUrl"])
        assertEquals("OWNER", mum["role"])
        assertNotNull(mum["joinedAt"])

        val samRow = members.forUser(sam)
        assertEquals("Sam", samRow["displayName"])
        assertNull(samRow["photoUrl"], "Sam has no photo")
        assertEquals("VIEWER", samRow["role"])
        assertNotNull(samRow["joinedAt"])
        assertEquals(12, samRow["starBalance"])
        assertEquals(3, samRow["currentStreak"])
        assertEquals(7, samRow["longestStreak"])
    }

    @Test
    fun `the owner comes first, then everyone else by join date, earliest first`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val early = signedIn("early")
        val middle = signedIn("middle")
        val late = signedIn("late")
        checklists.addMember(id, late, MemberRole.EDITOR)
        checklists.addMember(id, early, MemberRole.VIEWER)
        checklists.addMember(id, middle, MemberRole.EDITOR)
        // The owner joined last (as after a transfer), but still comes first.
        setJoinedAt(id, early, "2026-10-01T00:00:00Z")
        setJoinedAt(id, middle, "2026-10-02T00:00:00Z")
        setJoinedAt(id, late, "2026-10-03T00:00:00Z")
        setJoinedAt(id, owner, "2026-10-04T00:00:00Z")

        val order = membersAs(owner, id).map { it["userId"] }

        assertEquals(listOf(owner, early, middle, late).map { checklists.userId(it).toString() }, order)
    }

    @Test
    fun `members who joined at the same moment are ordered by userId`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val twins = (1..4).map { signedIn("twin-$it") }
        twins.forEach {
            checklists.addMember(id, it, MemberRole.VIEWER)
            setJoinedAt(id, it, "2026-10-01T00:00:00Z")
        }

        val order = membersAs(owner, id).drop(1).map { it["userId"] as String }

        // Sorted the way Postgres sorts uuids: byte by byte, the same as their lowercase text.
        assertEquals(twins.map { checklists.userId(it).toString() }.sorted(), order)
    }

    @Test
    fun `a member with no name or photo gets null for both`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val nameless = signedIn("nameless")
        checklists.addMember(id, nameless, MemberRole.VIEWER)
        val namelessId = checklists.userId(nameless)
        // Google didn't send a name. Set after their last request, which would sync it back.
        jdbc.update("UPDATE users SET display_name = NULL WHERE id = ?", namelessId)

        val row = membersAs(owner, id).single { it["userId"] == namelessId.toString() }

        // Sent as null, not left out, so the app can always read both fields.
        assertTrue("displayName" in row && row["displayName"] == null)
        assertTrue("photoUrl" in row && row["photoUrl"] == null)
    }

    @Test
    fun `removed members aren't listed`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val stays = signedIn("stays")
        val removed = signedIn("removed")
        checklists.addMember(id, stays, MemberRole.EDITOR)
        checklists.addMember(id, removed, MemberRole.EDITOR)
        markRemoved(id, removed)

        val ids = membersAs(owner, id).map { it["userId"] }

        assertEquals(listOf(owner, stays).map { checklists.userId(it).toString() }, ids)
    }

    // --- emails -------------------------------------------------------------

    @Test
    fun `the owner sees every member's email`() {
        val owner = signedIn("owner", email = "owner-${UUID.randomUUID()}@example.com")
        val id = checklists.createList(owner)
        val editorEmail = "editor-${UUID.randomUUID()}@example.com"
        val editor = signedIn("editor", email = editorEmail)
        checklists.addMember(id, editor, MemberRole.EDITOR)

        val members = membersAs(owner, id)

        assertEquals(editorEmail, members.forUser(editor)["email"])
        assertNotNull(members.forUser(owner)["email"])
    }

    @Test
    fun `editors get no email field at all`() {
        val id = checklists.createList(signedIn("owner"))
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        mvc.get("/api/checklists/$id/members") { with(editor) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[*].email") { doesNotExist() }
        }
    }

    @Test
    fun `viewers get no email field at all`() {
        val id = checklists.createList(signedIn("owner"))
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)

        mvc.get("/api/checklists/$id/members") { with(viewer) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[*].email") { doesNotExist() }
        }
    }

    // --- who can see it -----------------------------------------------------

    @Test
    fun `a super admin who isn't a member can see the members, with emails`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)

        val members = membersAs(superAdmin, id)

        assertEquals(1, members.size)
        assertNotNull(members.forUser(owner)["email"])
    }

    @Test
    fun `a super admin reading the members isn't audited`() {
        val id = checklists.createList(signedIn("owner"))

        membersAs(superAdmin, id)

        val auditRows = jdbc.queryForObject(
            "SELECT count(*) FROM admin_audit_log WHERE target_id = ?", Long::class.java, id,
        )
        assertEquals(0L, auditRows)
    }

    @Test
    fun `members can still be listed on an archived list`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val viewer = signedIn("viewer")
        checklists.addMember(id, viewer, MemberRole.VIEWER)
        mvc.post("/api/checklists/$id/archive") { with(owner) }.andExpect { status { isOk() } }

        assertEquals(2, membersAs(viewer, id).size)
    }

    @Test
    fun `an outsider gets 404`() {
        val id = checklists.createList(signedIn("owner"))

        mvc.get("/api/checklists/$id/members") { with(signedIn("outsider")) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `a removed member gets 404`() {
        val id = checklists.createList(signedIn("owner"))
        val removed = signedIn("removed")
        checklists.addMember(id, removed, MemberRole.EDITOR)
        markRemoved(id, removed)

        mvc.get("/api/checklists/$id/members") { with(removed) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `a list that doesn't exist gets 404`() {
        mvc.get("/api/checklists/${UUID.randomUUID()}/members") { with(signedIn("someone")) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }
}
