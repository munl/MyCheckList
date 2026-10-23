package com.mychecklist.support

import com.jayway.jsonpath.JsonPath
import com.mychecklist.checklist.MemberService
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.user.data.UserRepository
import org.springframework.boot.test.context.TestComponent
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * Sets up lists and members for API tests. Add it with `@Import(TestChecklists::class)`
 * (next to [TestcontainersConfig]) and inject it like any other bean.
 */
@TestComponent
class TestChecklists(
    private val mvc: MockMvc,
    private val users: UserRepository,
    private val memberService: MemberService,
    private val jdbc: JdbcTemplate,
) {

    /** Creates a list through the API, so [who] becomes its owner. Returns its ID. */
    fun createList(who: RequestPostProcessor, title: String = "Camping trip"): UUID {
        val body = mvc.post("/api/checklists") {
            with(who)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title": "$title", "description": "Things to pack"}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    /** The user ID behind a test token, creating the user row on first use. */
    fun userId(who: RequestPostProcessor): UUID {
        val email = mvc.get("/api/me") { with(who) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            .let { JsonPath.read<String>(it, "$.email") }
        return users.findByEmail(email)!!.id!!
    }

    /** Adds the user behind a test token to a list (restoring their old row if they were removed). */
    fun addMember(listId: UUID, who: RequestPostProcessor, role: MemberRole) {
        memberService.addOrRestore(listId, userId(who), role)
    }

    /**
     * Inserts an OWNER row with SQL, for tests that need a second owner row. The app never
     * adds owners this way: they come from creating a list or a transfer.
     */
    fun insertOwnerRow(listId: UUID, who: RequestPostProcessor) {
        jdbc.update(
            "INSERT INTO checklist_members (checklist_id, user_id, role) VALUES (?, ?, 'OWNER')",
            listId, userId(who),
        )
    }
}
