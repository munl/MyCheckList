package com.mychecklist

import com.mychecklist.checklist.data.ChecklistMemberRepository
import com.mychecklist.checklist.data.ChecklistRepository
import com.mychecklist.checklist.domain.Checklist
import com.mychecklist.checklist.domain.ChecklistMember
import com.mychecklist.checklist.domain.ChecklistMemberId
import com.mychecklist.checklist.domain.MemberRole
import com.mychecklist.occurrence.data.ChecklistOccurrenceRepository
import com.mychecklist.occurrence.data.OccurrenceItemAssigneeRepository
import com.mychecklist.occurrence.data.OccurrenceItemRepository
import com.mychecklist.occurrence.domain.ChecklistOccurrence
import com.mychecklist.occurrence.domain.OccurrenceItem
import com.mychecklist.occurrence.domain.OccurrenceItemAssignee
import com.mychecklist.occurrence.domain.OccurrenceItemAssigneeId
import com.mychecklist.schedule.data.ChecklistScheduleRepository
import com.mychecklist.schedule.domain.ChecklistSchedule
import com.mychecklist.schedule.domain.RepeatFrequency
import com.mychecklist.stars.data.StarTransactionRepository
import com.mychecklist.stars.domain.StarTransaction
import com.mychecklist.stars.domain.StarTransactionType
import com.mychecklist.support.TestcontainersConfig
import com.mychecklist.user.UserService
import com.mychecklist.user.domain.PlatformRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.oauth2.jwt.Jwt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Starting the app at all proves the Flyway migration runs and the JPA entities match
 * the schema (ddl-auto=validate). The tests then exercise the key rules end to end.
 */
@SpringBootTest(properties = ["app.super-admin-emails=boss@example.com"])
@Import(TestcontainersConfig::class)
class SchemaAndUserSyncTest @Autowired constructor(
    private val userService: UserService,
    private val checklists: ChecklistRepository,
    private val members: ChecklistMemberRepository,
    private val schedules: ChecklistScheduleRepository,
    private val occurrences: ChecklistOccurrenceRepository,
    private val occurrenceItems: OccurrenceItemRepository,
    private val occurrenceAssignees: OccurrenceItemAssigneeRepository,
    private val stars: StarTransactionRepository,
) {

    private fun token(uid: String, email: String, verified: Boolean = true): Jwt =
        Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject(uid)
            .claim("email", email)
            .claim("email_verified", verified)
            .claim("name", "Test $uid")
            .build()

    @Test
    fun `first sign-in creates the user and repeat sign-ins reuse it`() {
        val first = userService.syncFromToken(token("uid-a", "A@Example.com"))
        val again = userService.syncFromToken(token("uid-a", "a@example.com"))
        assertEquals(first.id, again.id)
        assertEquals("a@example.com", again.email)
        assertEquals(PlatformRole.USER, again.platformRole)
    }

    @Test
    fun `configured verified email becomes super admin, unverified does not`() {
        assertEquals(PlatformRole.SUPER_ADMIN, userService.syncFromToken(token("uid-boss", "boss@example.com")).platformRole)
        assertEquals(PlatformRole.USER, userService.syncFromToken(token("uid-boss", "boss@example.com", verified = false)).platformRole)
    }

    @Test
    fun `first person to complete an occurrence item wins, and a helper's tick counts`() {
        val owner = userService.syncFromToken(token("uid-o", "owner@example.com"))
        val friend = userService.syncFromToken(token("uid-f", "friend@example.com"))
        val list = checklists.save(Checklist(title = "Chores", createdBy = owner.id, timeZone = "America/Vancouver"))
        members.save(ChecklistMember(ChecklistMemberId(list.id!!, owner.id!!), MemberRole.OWNER))
        members.save(ChecklistMember(ChecklistMemberId(list.id!!, friend.id!!), MemberRole.EDITOR))
        schedules.save(
            ChecklistSchedule(
                checklistId = list.id!!,
                frequency = RepeatFrequency.WEEKLY,
                startsOn = LocalDate.of(2026, 10, 12),
                weekdayMask = ChecklistSchedule.maskOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY),
            ),
        )
        val today = LocalDate.of(2026, 10, 14)
        val zone = ZoneId.of(list.timeZone)
        val occurrence = occurrences.save(
            ChecklistOccurrence(
                checklistId = list.id!!,
                occurrenceDate = today,
                startsAt = today.atStartOfDay(zone).toInstant(),
                dueAt = today.plusDays(1).atStartOfDay(zone).toInstant(),
            ),
        )
        val item = occurrenceItems.save(
            OccurrenceItem(occurrenceId = occurrence.id!!, checklistId = list.id!!, title = "Dishes", position = 1000),
        )
        occurrenceAssignees.save(OccurrenceItemAssignee(OccurrenceItemAssigneeId(item.id!!, owner.id!!), list.id!!))

        // The friend helps out and ticks the owner's item first; the owner's tick is then a no-op.
        assertEquals(1, occurrenceItems.completeIfOpen(item.id!!, friend.id!!, Instant.now()))
        assertEquals(0, occurrenceItems.completeIfOpen(item.id!!, owner.id!!, Instant.now()))
        assertEquals(friend.id, occurrenceItems.findById(item.id!!).get().completedBy)

        val saved = schedules.findById(list.id!!).get()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY), saved.weekdays)
    }

    @Test
    fun `a star award can only be given once per person per occurrence unless voided`() {
        val owner = userService.syncFromToken(token("uid-s", "stars@example.com"))
        val list = checklists.save(Checklist(title = "Stars", createdBy = owner.id))
        members.save(ChecklistMember(ChecklistMemberId(list.id!!, owner.id!!), MemberRole.OWNER))
        val day = LocalDate.of(2026, 10, 14)
        val occurrence = occurrences.save(
            ChecklistOccurrence(checklistId = list.id!!, occurrenceDate = day, startsAt = Instant.now()),
        )
        fun award() = stars.saveAndFlush(
            StarTransaction(list.id!!, owner.id!!, 1, StarTransactionType.DAILY_COMPLETION, occurrenceId = occurrence.id),
        )

        val first = award()
        assertFailsWith<DataIntegrityViolationException> { award() }

        first.voidedAt = Instant.now()
        stars.saveAndFlush(first)
        award() // allowed again after the first one was voided (item unticked, then re-ticked)
        assertEquals(1L, stars.balanceOf(list.id!!, owner.id!!))
    }
}
