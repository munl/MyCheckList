package com.mychecklist.member

import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.support.SUPER_ADMIN_EMAIL
import com.mychecklist.support.TestChecklists
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.support.signedIn
import com.mychecklist.support.superAdmin
import com.mychecklist.user.data.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to a member's data when they're removed or leave (#10), in the same transaction
 * as the removal. Rules from #10, details agreed 2026-11-09:
 *  - they're unassigned from the list's template items
 *  - on every OPEN day of the list, they're unassigned from **unfinished** items only; finished
 *    items keep them (it shows who did the work, and their stars for it are kept)
 *  - CLOSED days keep all their assignments
 *  - their PENDING reward requests become CANCELLED, each with a REDEMPTION_REFUND ledger row
 *    and the stars back in their cached balance. `decided_by` on the request and `created_by` on
 *    the refund are whoever caused the removal (the owner or super admin who removed them, or the
 *    member themselves when they leave); the refund's reason is "Member removed" or "Left the list"
 *  - approved and rejected requests, the rest of their ledger and their member results are untouched
 *  - stars they already earned today are kept
 *  - nothing else (other members, other lists) changes, and a removal that fails cleans up nothing
 * Items, days and rewards have no API yet, so the data is set up with SQL.
 */
@SpringBootTest(properties = ["app.super-admin-emails=$SUPER_ADMIN_EMAIL"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestChecklists::class)
class RemovalCleanupTest @Autowired constructor(
    private val mvc: MockMvc,
    private val checklists: TestChecklists,
    private val jdbc: JdbcTemplate,
    private val memberService: MemberService,
    private val users: UserRepository,
    private val dataSource: DataSource,
) {

    /** The two ways someone stops being a member. */
    enum class Way(val refundReason: String) {
        REMOVED_BY_OWNER("Member removed"),
        LEFT("Left the list"),
    }

    /** A list with an owner and Sam (an editor), ready for Sam to go. */
    private inner class Setup {
        val owner = signedIn("owner")
        val listId = checklists.createList(owner)
        val sam = signedIn("Sam")
        val samId: UUID
        val ownerId: UUID = checklists.userId(owner)

        init {
            checklists.addMember(listId, sam, MemberRole.EDITOR)
            samId = checklists.userId(sam)
        }

        /** Sam goes [way]; returns the id of whoever caused it. */
        fun samGoes(way: Way): UUID {
            when (way) {
                Way.REMOVED_BY_OWNER ->
                    mvc.delete("/api/checklists/$listId/members/$samId") { with(owner) }
                        .andExpect { status { isNoContent() } }
                Way.LEFT ->
                    mvc.post("/api/checklists/$listId/leave") { with(sam) }
                        .andExpect { status { isNoContent() } }
            }
            return if (way == Way.LEFT) samId else ownerId
        }

        fun addMember(name: String, role: MemberRole = MemberRole.EDITOR): UUID {
            val who = signedIn(name)
            checklists.addMember(listId, who, role)
            return checklists.userId(who)
        }
    }

    // --- data setup (SQL) ---------------------------------------------------

    private var nextPosition = 1000

    private fun templateItem(listId: UUID, vararg assignees: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO checklist_items (id, checklist_id, title, position) VALUES (?, ?, 'Pack tent', ?)",
            id, listId, nextPosition++,
        )
        assignees.forEach {
            jdbc.update("INSERT INTO item_assignees (item_id, user_id, checklist_id) VALUES (?, ?, ?)", id, it, listId)
        }
        return id
    }

    private fun day(listId: UUID, date: LocalDate, open: Boolean): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO checklist_occurrences (id, checklist_id, occurrence_date, starts_at, status, closed_at) " +
                "VALUES (?, ?, ?, ?::timestamptz, ?, ${if (open) "NULL" else "now()"})",
            id, listId, date, date.toString(), if (open) "OPEN" else "CLOSED",
        )
        return id
    }

    private fun dayItem(listId: UUID, dayId: UUID, finishedBy: UUID?, vararg assignees: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO occurrence_items (id, occurrence_id, checklist_id, title, position, completed_at, completed_by) " +
                "VALUES (?, ?, ?, 'Pack tent', ?, ${if (finishedBy != null) "now()" else "NULL"}, ?)",
            id, dayId, listId, nextPosition++, finishedBy,
        )
        assignees.forEach {
            jdbc.update(
                "INSERT INTO occurrence_item_assignees (occurrence_item_id, user_id, checklist_id) VALUES (?, ?, ?)",
                id, it, listId,
            )
        }
        return id
    }

    private fun reward(listId: UUID, cost: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO rewards (id, checklist_id, title, star_cost) VALUES (?, ?, 'Ice cream', ?)", id, listId, cost)
        return id
    }

    /** Stars earned on [dayId], in the ledger and the cached balance, as the stars engine will do. */
    private fun earn(listId: UUID, userId: UUID, dayId: UUID, stars: Int) {
        jdbc.update(
            "INSERT INTO star_transactions (checklist_id, user_id, amount, type, occurrence_id) " +
                "VALUES (?, ?, ?, 'DAILY_COMPLETION', ?)",
            listId, userId, stars, dayId,
        )
        jdbc.update(
            "UPDATE checklist_members SET star_balance = star_balance + ? WHERE checklist_id = ? AND user_id = ?",
            stars, listId, userId,
        )
    }

    /**
     * A reward request with its stars held, as the rewards API will do. A decided request
     * ([status] not PENDING) gets a decision time, and a REJECTED one its refund.
     */
    private fun request(listId: UUID, userId: UUID, rewardId: UUID, cost: Int, status: String = "PENDING"): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO reward_redemptions (id, reward_id, checklist_id, requested_by, star_cost, status, decided_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ${if (status == "PENDING") "NULL" else "now()"})",
            id, rewardId, listId, userId, cost, status,
        )
        jdbc.update(
            "INSERT INTO star_transactions (checklist_id, user_id, amount, type, redemption_id) VALUES (?, ?, ?, 'REDEMPTION', ?)",
            listId, userId, -cost, id,
        )
        var balanceChange = -cost
        if (status == "REJECTED") {
            jdbc.update(
                "INSERT INTO star_transactions (checklist_id, user_id, amount, type, redemption_id) " +
                    "VALUES (?, ?, ?, 'REDEMPTION_REFUND', ?)",
                listId, userId, cost, id,
            )
            balanceChange = 0
        }
        jdbc.update(
            "UPDATE checklist_members SET star_balance = star_balance + ? WHERE checklist_id = ? AND user_id = ?",
            balanceChange, listId, userId,
        )
        return id
    }

    // --- reading it back ----------------------------------------------------

    private fun templateAssignees(itemId: UUID): Set<UUID> =
        jdbc.queryForList("SELECT user_id FROM item_assignees WHERE item_id = ?", UUID::class.java, itemId).toSet()

    private fun dayItemAssignees(itemId: UUID): Set<UUID> =
        jdbc.queryForList("SELECT user_id FROM occurrence_item_assignees WHERE occurrence_item_id = ?", UUID::class.java, itemId)
            .toSet()

    private fun balance(listId: UUID, userId: UUID): Int =
        jdbc.queryForObject(
            "SELECT star_balance FROM checklist_members WHERE checklist_id = ? AND user_id = ?",
            Int::class.java, listId, userId,
        )!!

    private fun requestRow(id: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT status, decided_by, decided_at FROM reward_redemptions WHERE id = ?", id)

    private fun refunds(redemptionId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT amount, user_id, checklist_id, created_by, reason FROM star_transactions " +
                "WHERE redemption_id = ? AND type = 'REDEMPTION_REFUND'",
            redemptionId,
        )

    /** Everything in the ledger for this person on this list, except refunds, as comparable rows. */
    private fun ledgerWithoutRefunds(listId: UUID, userId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT id, amount, type, occurrence_id, redemption_id, voided_at FROM star_transactions " +
                "WHERE checklist_id = ? AND user_id = ? AND type <> 'REDEMPTION_REFUND' ORDER BY id",
            listId, userId,
        )

    private val today: LocalDate = LocalDate.now()

    // =========================================================================
    // Assignments
    // =========================================================================

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `they're unassigned from the list's template items, and others stay assigned`(way: Way) {
        val s = Setup()
        val alex = s.addMember("Alex")
        val shared = templateItem(s.listId, s.samId, alex)
        val samsOnly = templateItem(s.listId, s.samId)

        s.samGoes(way)

        assertEquals(setOf(alex), templateAssignees(shared))
        assertEquals(emptySet(), templateAssignees(samsOnly))
    }

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `on today's open day they're unassigned from unfinished items, and others stay assigned`(way: Way) {
        val s = Setup()
        val alex = s.addMember("Alex")
        val open = day(s.listId, today, open = true)
        val shared = dayItem(s.listId, open, null, s.samId, alex)
        val samsOnly = dayItem(s.listId, open, null, s.samId)

        s.samGoes(way)

        assertEquals(setOf(alex), dayItemAssignees(shared))
        assertEquals(emptySet(), dayItemAssignees(samsOnly))
    }

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `on an open day, finished items keep them as assignee`(way: Way) {
        val s = Setup()
        val alex = s.addMember("Alex")
        val open = day(s.listId, today, open = true)
        val finishedBySam = dayItem(s.listId, open, s.samId, s.samId)
        val finishedByAlex = dayItem(s.listId, open, alex, s.samId, alex)

        s.samGoes(way)

        assertEquals(setOf(s.samId), dayItemAssignees(finishedBySam))
        assertEquals(setOf(s.samId, alex), dayItemAssignees(finishedByAlex))
    }

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `an earlier day that's still open is cleaned up too`(way: Way) {
        val s = Setup()
        val yesterday = day(s.listId, today.minusDays(1), open = true)
        val unfinished = dayItem(s.listId, yesterday, null, s.samId)
        val finished = dayItem(s.listId, yesterday, s.samId, s.samId)

        s.samGoes(way)

        assertEquals(emptySet(), dayItemAssignees(unfinished))
        assertEquals(setOf(s.samId), dayItemAssignees(finished))
    }

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `closed days keep all their assignments, finished or not`(way: Way) {
        val s = Setup()
        val closed = day(s.listId, today.minusDays(2), open = false)
        val unfinished = dayItem(s.listId, closed, null, s.samId)
        val finished = dayItem(s.listId, closed, s.samId, s.samId)

        s.samGoes(way)

        assertEquals(setOf(s.samId), dayItemAssignees(unfinished))
        assertEquals(setOf(s.samId), dayItemAssignees(finished))
    }

    @Test
    fun `their assignments on other lists are untouched`() {
        val s = Setup()
        val other = checklists.createList(signedIn("other owner"))
        checklists.addMember(other, s.sam, MemberRole.EDITOR)
        val otherTemplate = templateItem(other, s.samId)
        val otherDayItem = dayItem(other, day(other, today, open = true), null, s.samId)

        s.samGoes(Way.LEFT)

        assertEquals(setOf(s.samId), templateAssignees(otherTemplate))
        assertEquals(setOf(s.samId), dayItemAssignees(otherDayItem))
    }

    @Test
    fun `a tick that's saving while they're removed keeps them on the finished item (PR #33 review)`() {
        val s = Setup()
        val item = dayItem(s.listId, day(s.listId, today, open = true), null, s.samId)
        val ownerUser = users.findById(s.ownerId).get()
        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { tick ->
                // Sam's tick has updated the item but not saved yet...
                tick.autoCommit = false
                tick.prepareStatement(
                    "UPDATE occurrence_items SET completed_at = now(), completed_by = ? WHERE id = ? AND completed_at IS NULL",
                ).use { it.setObject(1, s.samId); it.setObject(2, item); it.executeUpdate() }

                // ...when the owner removes him.
                val removal = executor.submit { memberService.remove(ownerUser, s.listId, s.samId) }
                waitUntilBlockedOrDone { removal.isDone }
                tick.commit()
                removal.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }

        val finished = jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM occurrence_items WHERE id = ?", Boolean::class.java, item)
        assertEquals(true, finished)
        assertEquals(setOf(s.samId), dayItemAssignees(item), "the finished item still shows who did it")
    }

    /** Waits until a statement is waiting on a row lock, or [done] (it never had to wait). */
    private fun waitUntilBlockedOrDone(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (done()) return
            val waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long::class.java,
            )
            if (waiting!! > 0) return
            Thread.sleep(20)
        }
        error("the removal neither finished nor waited on a lock")
    }

    // =========================================================================
    // Reward requests
    // =========================================================================

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `pending requests are cancelled and refunded, recording who caused it`(way: Way) {
        val s = Setup()
        val closed = day(s.listId, today.minusDays(1), open = false)
        earn(s.listId, s.samId, closed, 10)
        val iceCream = request(s.listId, s.samId, reward(s.listId, 3), 3)
        val movie = request(s.listId, s.samId, reward(s.listId, 5), 5)
        assertEquals(2, balance(s.listId, s.samId))

        val causedBy = s.samGoes(way)

        for ((id, cost) in listOf(iceCream to 3, movie to 5)) {
            val row = requestRow(id)
            assertEquals("CANCELLED", row["status"])
            assertEquals(causedBy, row["decided_by"])
            assertNotNull(row["decided_at"])

            val refund = refunds(id).single()
            assertEquals(cost, refund["amount"])
            assertEquals(s.samId, refund["user_id"])
            assertEquals(s.listId, refund["checklist_id"])
            assertEquals(causedBy, refund["created_by"])
            assertEquals(way.refundReason, refund["reason"])
        }
        assertEquals(10, balance(s.listId, s.samId), "both held amounts are back in the balance")
    }

    @Test
    fun `a super admin's removal records the super admin`() {
        val s = Setup()
        earn(s.listId, s.samId, day(s.listId, today.minusDays(1), open = false), 4)
        val pending = request(s.listId, s.samId, reward(s.listId, 4), 4)

        mvc.delete("/api/checklists/${s.listId}/members/${s.samId}") { with(superAdmin) }
            .andExpect { status { isNoContent() } }

        val bossId = checklists.userId(superAdmin)
        assertEquals(bossId, requestRow(pending)["decided_by"])
        assertEquals(bossId, refunds(pending).single()["created_by"])
        assertEquals("Member removed", refunds(pending).single()["reason"])
        assertEquals(4, balance(s.listId, s.samId))
    }

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `approved and rejected requests are untouched`(way: Way) {
        val s = Setup()
        earn(s.listId, s.samId, day(s.listId, today.minusDays(1), open = false), 10)
        val reward = reward(s.listId, 2)
        val approved = request(s.listId, s.samId, reward, 2, status = "APPROVED")
        val rejected = request(s.listId, s.samId, reward, 2, status = "REJECTED")
        val approvedBefore = requestRow(approved)
        val rejectedBefore = requestRow(rejected)
        assertEquals(8, balance(s.listId, s.samId))

        s.samGoes(way)

        assertEquals(approvedBefore, requestRow(approved))
        assertEquals(rejectedBefore, requestRow(rejected))
        assertTrue(refunds(approved).isEmpty(), "an approved request stays spent")
        assertEquals(1, refunds(rejected).size, "a rejected request isn't refunded twice")
        assertEquals(8, balance(s.listId, s.samId))
    }

    @Test
    fun `other members' pending requests are untouched`() {
        val s = Setup()
        val alex = s.addMember("Alex")
        earn(s.listId, alex, day(s.listId, today.minusDays(1), open = false), 5)
        val alexsRequest = request(s.listId, alex, reward(s.listId, 3), 3)

        s.samGoes(Way.REMOVED_BY_OWNER)

        assertEquals("PENDING", requestRow(alexsRequest)["status"])
        assertNull(requestRow(alexsRequest)["decided_at"])
        assertTrue(refunds(alexsRequest).isEmpty())
        assertEquals(2, balance(s.listId, alex))
    }

    @Test
    fun `their pending requests on other lists are untouched`() {
        val s = Setup()
        val other = checklists.createList(signedIn("other owner"))
        checklists.addMember(other, s.sam, MemberRole.EDITOR)
        earn(other, s.samId, day(other, today.minusDays(1), open = false), 5)
        val elsewhere = request(other, s.samId, reward(other, 3), 3)

        s.samGoes(Way.LEFT)

        assertEquals("PENDING", requestRow(elsewhere)["status"])
        assertTrue(refunds(elsewhere).isEmpty())
        assertEquals(2, balance(other, s.samId))
    }

    // =========================================================================
    // Stars and history are kept
    // =========================================================================

    @ParameterizedTest
    @EnumSource(Way::class)
    fun `stars earned today, the rest of the ledger and member results are kept`(way: Way) {
        val s = Setup()
        val open = day(s.listId, today, open = true)
        val closed = day(s.listId, today.minusDays(1), open = false)
        dayItem(s.listId, open, s.samId, s.samId)
        earn(s.listId, s.samId, open, 2)
        earn(s.listId, s.samId, closed, 1)
        request(s.listId, s.samId, reward(s.listId, 1), 1, status = "APPROVED")
        for ((dayId, stars) in listOf(open to 2, closed to 1)) {
            jdbc.update(
                "INSERT INTO occurrence_member_results " +
                    "(occurrence_id, user_id, checklist_id, assigned_count, completed_count, stars_awarded, streak_after) " +
                    "VALUES (?, ?, ?, 1, 1, ?, 1)",
                dayId, s.samId, s.listId, stars,
            )
        }
        val ledgerBefore = ledgerWithoutRefunds(s.listId, s.samId)
        val resultsBefore = jdbc.queryForList(
            "SELECT * FROM occurrence_member_results WHERE checklist_id = ? AND user_id = ? ORDER BY occurrence_id",
            s.listId, s.samId,
        )
        assertEquals(2, balance(s.listId, s.samId))

        s.samGoes(way)

        assertEquals(ledgerBefore, ledgerWithoutRefunds(s.listId, s.samId), "nothing voided, added or removed")
        assertEquals(
            resultsBefore,
            jdbc.queryForList(
                "SELECT * FROM occurrence_member_results WHERE checklist_id = ? AND user_id = ? ORDER BY occurrence_id",
                s.listId, s.samId,
            ),
        )
        assertEquals(2, balance(s.listId, s.samId), "today's stars are kept")
    }

    // =========================================================================
    // A removal that fails cleans up nothing
    // =========================================================================

    @Test
    fun `when the owner tries to leave (409), nothing of theirs is cleaned up`() {
        val s = Setup()
        val template = templateItem(s.listId, s.ownerId)
        val unfinished = dayItem(s.listId, day(s.listId, today, open = true), null, s.ownerId)
        earn(s.listId, s.ownerId, day(s.listId, today.minusDays(1), open = false), 5)
        val pending = request(s.listId, s.ownerId, reward(s.listId, 3), 3)

        mvc.post("/api/checklists/${s.listId}/leave") { with(s.owner) }.andExpect { status { isConflict() } }

        assertEquals(setOf(s.ownerId), templateAssignees(template))
        assertEquals(setOf(s.ownerId), dayItemAssignees(unfinished))
        assertEquals("PENDING", requestRow(pending)["status"])
        assertTrue(refunds(pending).isEmpty())
        assertEquals(2, balance(s.listId, s.ownerId))
    }

    @Test
    fun `when an editor tries to remove someone (403), nothing of theirs is cleaned up`() {
        val s = Setup()
        val alex = s.addMember("Alex", MemberRole.VIEWER)
        val template = templateItem(s.listId, alex)
        earn(s.listId, alex, day(s.listId, today.minusDays(1), open = false), 5)
        val pending = request(s.listId, alex, reward(s.listId, 3), 3)

        mvc.delete("/api/checklists/${s.listId}/members/$alex") { with(s.sam) }.andExpect { status { isForbidden() } }

        assertEquals(setOf(alex), templateAssignees(template))
        assertEquals("PENDING", requestRow(pending)["status"])
        assertEquals(2, balance(s.listId, alex))
    }
}
