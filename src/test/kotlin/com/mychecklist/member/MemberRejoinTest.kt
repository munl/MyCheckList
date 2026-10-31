package com.mychecklist.member

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.common.error.ConflictException
import com.mychecklist.common.error.NotFoundException
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A removed member who joins again gets their old row back (stars and streaks kept),
 * with the role they're rejoining as. See "Removing members" in PROGRESS.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class MemberRejoinTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val memberService: MemberService,
    private val members: ChecklistMemberRepository,
    private val jdbc: JdbcTemplate,
) {

    private fun markRemoved(listId: UUID, userId: UUID) {
        jdbc.update(
            "UPDATE checklist_members SET removed_at = now() WHERE checklist_id = ? AND user_id = ?",
            listId, userId,
        )
    }

    @Test
    fun `a rejoining member keeps their stars and streaks`() {
        val id = checklists.createList(signedIn("owner"))
        val mia = signedIn("mia")
        val miaId = checklists.userId(mia)
        memberService.addOrRestore(id, miaId, MemberRole.EDITOR)
        jdbc.update(
            "UPDATE checklist_members SET star_balance = 120, current_streak = 5, longest_streak = 9 " +
                "WHERE checklist_id = ? AND user_id = ?",
            id, miaId,
        )
        markRemoved(id, miaId)

        memberService.addOrRestore(id, miaId, MemberRole.VIEWER)

        val row = members.findById(ChecklistMemberId(id, miaId)).get()
        assertNull(row.removedAt)
        assertEquals(MemberRole.VIEWER, row.role)
        assertEquals(120, row.starBalance)
        assertEquals(5, row.currentStreak)
        assertEquals(9, row.longestStreak)
        mvc.get("/api/checklists/$id") { with(mia) }.andExpect { status { isOk() } }
    }

    private fun joinedAt(listId: UUID, userId: UUID): Instant =
        jdbc.queryForObject(
            "SELECT joined_at FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            Timestamp::class.java, listId, userId,
        )!!.toInstant()

    private fun setJoinedAt(listId: UUID, userId: UUID, joinedAt: Instant) {
        jdbc.update(
            "UPDATE checklist_members SET joined_at = ? WHERE checklist_id = ? AND user_id = ?",
            Timestamp.from(joinedAt), listId, userId,
        )
    }

    @Test
    fun `a rejoining member gets a new join date`() {
        val id = checklists.createList(signedIn("owner"))
        val samId = checklists.userId(signedIn("sam"))
        memberService.addOrRestore(id, samId, MemberRole.EDITOR)
        val firstJoin = Instant.parse("2026-01-01T00:00:00Z")
        setJoinedAt(id, samId, firstJoin)
        markRemoved(id, samId)

        val before = Instant.now().minusSeconds(60)
        val restored = memberService.addOrRestore(id, samId, MemberRole.VIEWER)

        val rejoined = joinedAt(id, samId)
        assertTrue(rejoined.isAfter(before), "expected a new join date, got $rejoined")
        // Callers (e.g. accepting an invite) may show the returned row, so it must have the new date too.
        assertEquals(rejoined, restored.joinedAt)
    }

    @Test
    fun `adding someone who is already an active member keeps their join date`() {
        val id = checklists.createList(signedIn("owner"))
        val samId = checklists.userId(signedIn("sam"))
        memberService.addOrRestore(id, samId, MemberRole.EDITOR)
        val firstJoin = Instant.parse("2026-01-01T00:00:00Z")
        setJoinedAt(id, samId, firstJoin)

        memberService.addOrRestore(id, samId, MemberRole.VIEWER)

        assertEquals(firstJoin, joinedAt(id, samId))
    }

    @Test
    fun `a removed owner can rejoin with a lower role while someone else owns the list`() {
        val oldOwner = signedIn("old-owner")
        val oldOwnerId = checklists.userId(oldOwner)
        val id = checklists.createList(oldOwner)
        markRemoved(id, oldOwnerId)
        val newOwner = signedIn("new-owner")
        checklists.insertOwnerRow(id, newOwner)

        memberService.addOrRestore(id, oldOwnerId, MemberRole.EDITOR)

        mvc.get("/api/checklists/$id") { with(oldOwner) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("EDITOR") }
        }
        mvc.get("/api/checklists/$id") { with(newOwner) }.andExpect { jsonPath("$.myRole") { value("OWNER") } }
    }

    @Test
    fun `adding someone who is already an active member changes nothing`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val editor = signedIn("editor")
        checklists.addMember(id, editor, MemberRole.EDITOR)

        memberService.addOrRestore(id, checklists.userId(owner), MemberRole.VIEWER)
        memberService.addOrRestore(id, checklists.userId(editor), MemberRole.VIEWER)

        mvc.get("/api/checklists/$id") { with(owner) }.andExpect { jsonPath("$.myRole") { value("OWNER") } }
        mvc.get("/api/checklists/$id") { with(editor) }.andExpect { jsonPath("$.myRole") { value("EDITOR") } }
    }

    @Test
    fun `nobody can be added or restored as owner`() {
        val oldOwner = signedIn("old-owner")
        val oldOwnerId = checklists.userId(oldOwner)
        val id = checklists.createList(oldOwner)
        markRemoved(id, oldOwnerId)

        assertFailsWith<IllegalArgumentException> {
            memberService.addOrRestore(id, checklists.userId(signedIn("newcomer")), MemberRole.OWNER)
        }
        assertFailsWith<IllegalArgumentException> {
            memberService.addOrRestore(id, oldOwnerId, MemberRole.OWNER)
        }
    }

    @Test
    fun `nobody can join or rejoin an archived list`() {
        val owner = signedIn("owner")
        val id = checklists.createList(owner)
        val removedId = checklists.userId(signedIn("removed"))
        memberService.addOrRestore(id, removedId, MemberRole.EDITOR)
        markRemoved(id, removedId)
        mvc.post("/api/checklists/$id/archive") { with(owner) }.andExpect { status { isOk() } }

        assertFailsWith<ConflictException> { memberService.addOrRestore(id, removedId, MemberRole.EDITOR) }
        assertNotNull(members.findById(ChecklistMemberId(id, removedId)).get().removedAt)

        val newcomerId = checklists.userId(signedIn("newcomer"))
        assertFailsWith<ConflictException> { memberService.addOrRestore(id, newcomerId, MemberRole.VIEWER) }
        assertFalse(members.existsById(ChecklistMemberId(id, newcomerId)))
    }

    @Test
    fun `joining a list that doesn't exist gives not found`() {
        val userId = checklists.userId(signedIn("newcomer"))

        assertFailsWith<NotFoundException> { memberService.addOrRestore(UUID.randomUUID(), userId, MemberRole.VIEWER) }
    }
}
