package com.mychecklist

import com.jayway.jsonpath.JsonPath
import com.mychecklist.admin.AdminAuditLogRepository
import com.mychecklist.checklist.ChecklistMember
import com.mychecklist.checklist.ChecklistMemberId
import com.mychecklist.checklist.ChecklistMemberRepository
import com.mychecklist.checklist.MemberRole
import com.mychecklist.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests of the checklist endpoints and the permission rules behind them.
 * Requests go through the real Spring Security filter chain; `jwt()` stands in for a
 * verified Firebase token. Each test uses fresh users so tests don't affect each other.
 */
@SpringBootTest(properties = ["app.super-admin-emails=boss@example.com"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class ChecklistApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val users: UserRepository,
    private val members: ChecklistMemberRepository,
    private val auditLog: AdminAuditLogRepository,
) {

    // --- helpers ------------------------------------------------------------

    /** A signed-in test user, identified by a fake Firebase token. */
    private fun signedIn(
        name: String,
        email: String = "$name-${UUID.randomUUID()}@example.com",
        uid: String = "uid-$email",
    ): RequestPostProcessor =
        jwt().jwt {
            it.subject(uid)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", name)
        }

    /** Matches app.super-admin-emails above (same uid as SchemaAndUserSyncTest, in case they share a database). */
    private val superAdmin = signedIn("boss", email = "boss@example.com", uid = "uid-boss")

    private fun createList(who: RequestPostProcessor, title: String = "Camping trip"): UUID {
        val body = mvc.post("/api/checklists") {
            with(who)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "$title", "description": "Things to pack"}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    /** Looks up (creating if needed) the user row behind a test token, then adds them to a list. */
    private fun addMember(listId: UUID, who: RequestPostProcessor, role: MemberRole) {
        val email = mvc.get("/api/me") { with(who) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            .let { JsonPath.read<String>(it, "$.email") }
        val user = users.findByEmail(email)!!
        members.save(ChecklistMember(ChecklistMemberId(listId, user.id!!), role))
    }

    private fun rename(listId: UUID, who: RequestPostProcessor, title: String = "Renamed") =
        mvc.patch("/api/checklists/$listId") {
            with(who)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "$title"}"""
        }

    // --- happy path ---------------------------------------------------------

    @Test
    fun `owner can create, read, rename, archive, unarchive and delete a list`() {
        val owner = signedIn("owner")
        val id = createList(owner)

        mvc.get("/api/checklists/$id") { with(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Camping trip") }
            jsonPath("$.myRole") { value("OWNER") }
            jsonPath("$.memberCount") { value(1) }
            jsonPath("$.itemCount") { value(0) }
            jsonPath("$.archived") { value(false) }
        }
        mvc.get("/api/checklists") { with(owner) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(id.toString()) }
        }

        rename(id, owner).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Renamed") }
        }

        mvc.post("/api/checklists/$id/archive") { with(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.archived") { value(true) }
        }
        mvc.get("/api/checklists") { with(owner) }.andExpect { jsonPath("$.length()") { value(0) } }
        mvc.get("/api/checklists?archived=true") { with(owner) }.andExpect { jsonPath("$[0].id") { value(id.toString()) } }

        mvc.post("/api/checklists/$id/unarchive") { with(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.archived") { value(false) }
        }

        mvc.delete("/api/checklists/$id?confirm=true") { with(owner) }.andExpect { status { isNoContent() } }
        mvc.get("/api/checklists/$id") { with(owner) }.andExpect { status { isNotFound() } }
    }

    // --- authentication -----------------------------------------------------

    @Test
    fun `requests without a token are rejected with 401`() {
        mvc.get("/api/checklists").andExpect { status { isUnauthorized() } }
        mvc.post("/api/checklists") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "x"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    // --- authorization ------------------------------------------------------

    @Test
    fun `outsiders get 404 for everything, the same as for a list that doesn't exist`() {
        val id = createList(signedIn("owner"))
        val outsider = signedIn("outsider")

        mvc.get("/api/checklists/$id") { with(outsider) }.andExpect { status { isNotFound() } }
        rename(id, outsider).andExpect { status { isNotFound() } }
        mvc.post("/api/checklists/$id/archive") { with(outsider) }.andExpect { status { isNotFound() } }
        mvc.delete("/api/checklists/$id?confirm=true") { with(outsider) }.andExpect { status { isNotFound() } }
        mvc.get("/api/checklists") { with(outsider) }.andExpect { jsonPath("$.length()") { value(0) } }

        // Indistinguishable from a random ID:
        mvc.get("/api/checklists/${UUID.randomUUID()}") { with(outsider) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("Checklist not found") }
        }
    }

    @Test
    fun `viewers can read but not change anything`() {
        val id = createList(signedIn("owner"))
        val viewer = signedIn("viewer")
        addMember(id, viewer, MemberRole.VIEWER)

        mvc.get("/api/checklists/$id") { with(viewer) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("VIEWER") }
            jsonPath("$.memberCount") { value(2) }
        }
        rename(id, viewer).andExpect { status { isForbidden() } }
        mvc.post("/api/checklists/$id/archive") { with(viewer) }.andExpect { status { isForbidden() } }
        mvc.delete("/api/checklists/$id?confirm=true") { with(viewer) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `editors can edit but not archive or delete`() {
        val id = createList(signedIn("owner"))
        val editor = signedIn("editor")
        addMember(id, editor, MemberRole.EDITOR)

        rename(id, editor, "Edited by editor").andExpect { status { isOk() } }
        mvc.post("/api/checklists/$id/archive") { with(editor) }.andExpect { status { isForbidden() } }
        mvc.delete("/api/checklists/$id?confirm=true") { with(editor) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `archived lists are read-only`() {
        val owner = signedIn("owner")
        val id = createList(owner)
        mvc.post("/api/checklists/$id/archive") { with(owner) }.andExpect { status { isOk() } }

        mvc.get("/api/checklists/$id") { with(owner) }.andExpect { status { isOk() } }
        rename(id, owner).andExpect { status { isConflict() } }
    }

    @Test
    fun `super admin can change any list and the change is audited`() {
        val id = createList(signedIn("owner"))

        rename(id, superAdmin, "Fixed by admin").andExpect {
            status { isOk() }
            jsonPath("$.myRole") { doesNotExist() }
        }

        val entries = auditLog.findAll().filter { it.targetId == id }
        assertEquals(listOf("UPDATE_CHECKLIST"), entries.map { it.action })
    }

    @Test
    fun `owners editing their own list are not audited`() {
        val owner = signedIn("owner")
        val id = createList(owner)
        rename(id, owner).andExpect { status { isOk() } }
        assertTrue(auditLog.findAll().none { it.targetId == id })
    }

    // --- validation and errors ----------------------------------------------

    @Test
    fun `invalid input is rejected with field errors and no internals`() {
        val owner = signedIn("owner")
        mvc.post("/api/checklists") {
            with(owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "   "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("title") }
            jsonPath("$.trace") { doesNotExist() }
            jsonPath("$.exception") { doesNotExist() }
        }

        val tooLong = "x".repeat(201)
        mvc.post("/api/checklists") {
            with(owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "$tooLong"}"""
        }.andExpect { status { isBadRequest() } }

        mvc.post("/api/checklists") {
            with(owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{ not json"""
        }.andExpect { status { isBadRequest() } }

        mvc.get("/api/checklists/not-a-uuid") { with(owner) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `delete requires explicit confirmation`() {
        val owner = signedIn("owner")
        val id = createList(owner)
        mvc.delete("/api/checklists/$id") { with(owner) }.andExpect { status { isBadRequest() } }
        mvc.get("/api/checklists/$id") { with(owner) }.andExpect { status { isOk() } }
    }
}
