package com.mychecklist.checklist

import com.jayway.jsonpath.JsonPath
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import org.junit.jupiter.api.Disabled
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Members are soft removed: leaving or being removed sets `checklist_members.removed_at`
 * and keeps the row. From then on they're treated like an outsider (#2).
 *
 * No endpoint removes members yet, so [markRemoved] sets the column with SQL.
 */
@Disabled("Waiting on #3")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class RemovedMemberAccessTest @Autowired constructor(
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

    /** The caller's `GET /api/checklists` response, one map per list. */
    private fun myLists(who: RequestPostProcessor): List<Map<String, Any?>> {
        val body = mvc.get("/api/checklists") { with(who) }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return JsonPath.read(body, "$")
    }

    private fun myListIds(who: RequestPostProcessor): List<UUID> = myLists(who).map { UUID.fromString(it["id"] as String) }

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
        checklists.addMember(id, newOwner, MemberRole.OWNER)

        mvc.get("/api/checklists/$id") { with(newOwner) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("OWNER") }
        }
    }
}
