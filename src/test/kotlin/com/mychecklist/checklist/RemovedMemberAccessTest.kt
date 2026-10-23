package com.mychecklist.checklist

import com.jayway.jsonpath.JsonPath
import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import org.junit.jupiter.api.Test
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Members are soft removed: leaving or being removed sets `checklist_members.removed_at`
 * and keeps the row. From then on they're treated like an outsider (#2).
 *
 * No endpoint removes members yet, so [markRemoved] sets the column with SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class RemovedMemberAccessTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val members: ChecklistMemberRepository,
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

    /** The caller's `GET /api/checklists` response (or `?archived=true`), one map per list. */
    private fun myLists(who: RequestPostProcessor, archived: Boolean = false): List<Map<String, Any?>> {
        val body = mvc.get("/api/checklists?archived=$archived") { with(who) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return JsonPath.read(body, "$")
    }

    private fun myListIds(who: RequestPostProcessor, archived: Boolean = false): List<UUID> =
        myLists(who, archived).map { UUID.fromString(it["id"] as String) }

    /** A list with an owner and an editor, where the editor has been removed. */
    private fun listWithRemovedEditor(): Triple<UUID, RequestPostProcessor, RequestPostProcessor> {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)
        markRemoved(id, editor)
        return Triple(id, owner, editor)
    }

    // --- access -------------------------------------------------------------

    @Test
    fun `a removed member gets 404 when reading the list, the same as an outsider`() {
        val (id, _, removed) = listWithRemovedEditor()

        mvc.get("/api/checklists/$id") { with(removed) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `a removed member gets 404 when changing the list`() {
        val (id, _, removed) = listWithRemovedEditor()

        mvc.patch("/api/checklists/$id") {
            with(removed)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "Sneaky rename"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `the list no longer appears in a removed member's lists`() {
        val (id, _, removed) = listWithRemovedEditor()

        assertFalse(id in myListIds(removed))
    }

    @Test
    fun `a removed member doesn't see the list among their archived lists either`() {
        val (id, owner, removed) = listWithRemovedEditor()
        mvc.post("/api/checklists/$id/archive") { with(owner) }.andExpect { status { isOk() } }
        assertTrue(id in myListIds(owner, archived = true))

        assertFalse(id in myListIds(removed, archived = true))
        mvc.get("/api/checklists/$id") { with(removed) }.andExpect { status { isNotFound() } }
    }

    // --- counts and constraints ---------------------------------------------

    @Test
    fun `a removed member isn't counted in memberCount`() {
        val (id, owner, _) = listWithRemovedEditor()

        mvc.get("/api/checklists/$id") { with(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.memberCount") { value(1) }
        }
        val inMyLists = myLists(owner).single { it["id"] == id.toString() }
        assertEquals(1, inMyLists["memberCount"])
    }

    @Test
    fun `a removed owner row doesn't stop another member becoming owner`() {
        val oldOwner = signedIn("old-owner")
        val id = checklists.createList(oldOwner)
        markRemoved(id, oldOwner)

        val newOwner = signedIn("new-owner")
        checklists.insertOwnerRow(id, newOwner)

        mvc.get("/api/checklists/$id") { with(newOwner) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("OWNER") }
        }
    }

    @Test
    fun `a removed owner row doesn't count towards the owned-lists limit`() {
        // Checked on the count query itself, since reaching the real limit would take 200 lists.
        val owner = signedIn("owner")
        val ownerId = checklists.userId(owner)
        val id = checklists.createList(owner)
        assertEquals(1, members.countByIdUserIdAndRoleAndRemovedAtIsNull(ownerId, MemberRole.OWNER))

        markRemoved(id, owner)

        assertEquals(0, members.countByIdUserIdAndRoleAndRemovedAtIsNull(ownerId, MemberRole.OWNER))
    }
}
