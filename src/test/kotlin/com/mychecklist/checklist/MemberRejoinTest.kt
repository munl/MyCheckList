package com.mychecklist.checklist

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `a removed owner can rejoin with a lower role while someone else owns the list`() {
        val oldOwner = signedIn("old-owner")
        val oldOwnerId = checklists.userId(oldOwner)
        val id = checklists.createList(oldOwner)
        markRemoved(id, oldOwnerId)
        val newOwner = signedIn("new-owner")
        memberService.addOrRestore(id, checklists.userId(newOwner), MemberRole.OWNER)

        memberService.addOrRestore(id, oldOwnerId, MemberRole.EDITOR)

        mvc.get("/api/checklists/$id") { with(oldOwner) }.andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("EDITOR") }
        }
        mvc.get("/api/checklists/$id") { with(newOwner) }.andExpect { jsonPath("$.myRole") { value("OWNER") } }
    }
}
