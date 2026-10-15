package com.mychecklist.checklist

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.io.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RepeatFrequency { DAILY, WEEKLY, MONTHLY }

enum class OccurrenceStatus { OPEN, CLOSED }

/**
 * How a list repeats. A list with no schedule row is a one-time list.
 *
 * Examples:
 *   Daily              DAILY,   repeatInterval=1
 *   Every 3 days       DAILY,   repeatInterval=3
 *   Mon, Wed, Sat      WEEKLY,  weekdayMask = maskOf(MONDAY, WEDNESDAY, SATURDAY)
 *   Every other Friday WEEKLY,  repeatInterval=2, weekdayMask = maskOf(FRIDAY)
 *   15th of the month  MONTHLY, monthDay=15
 *   Last Friday        MONTHLY, monthWeek=-1, monthWeekday=5
 */
@Entity
@Table(name = "checklist_schedules")
class ChecklistSchedule(
    @Id
    @Column(name = "checklist_id")
    var checklistId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var frequency: RepeatFrequency,

    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate,

    /** "Every N" days/weeks/months. */
    @Column(name = "repeat_interval", nullable = false)
    var repeatInterval: Int = 1,

    /** WEEKLY only. Bit per weekday: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64. */
    @Column(name = "weekday_mask", nullable = false)
    var weekdayMask: Int = 0,

    /** MONTHLY on a date (1–31; 31 means the last day in shorter months). */
    @Column(name = "month_day")
    var monthDay: Int? = null,

    /** MONTHLY on "the Nth weekday": 1–4, or -1 for the last. Used with [monthWeekday]. */
    @Column(name = "month_week")
    var monthWeek: Int? = null,

    /** ISO weekday 1=Mon … 7=Sun. */
    @Column(name = "month_weekday")
    var monthWeekday: Int? = null,

    @Column(name = "ends_on")
    var endsOn: LocalDate? = null,

    @Column(name = "max_occurrences")
    var maxOccurrences: Int? = null,
) {
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    val weekdays: Set<DayOfWeek>
        get() = DayOfWeek.values().filter { weekdayMask and (1 shl (it.value - 1)) != 0 }.toSet()

    companion object {
        fun maskOf(vararg days: DayOfWeek): Int = days.fold(0) { mask, d -> mask or (1 shl (d.value - 1)) }
    }
}

/** One copy of the list for one day/week/... it's due. */
@Entity
@Table(name = "checklist_occurrences")
class ChecklistOccurrence(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    /** The day this occurrence is for, in the list's time zone. */
    @Column(name = "occurrence_date", nullable = false)
    var occurrenceDate: LocalDate,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    /** When it closes. Null for a one-time list with no deadline. */
    @Column(name = "due_at")
    var dueAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: OccurrenceStatus = OccurrenceStatus.OPEN

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    /** When every member with items had finished theirs. */
    @Column(name = "team_completed_at")
    var teamCompletedAt: Instant? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
}

/** An item as it was when its occurrence was created. Completion lives here. */
@Entity
@Table(name = "occurrence_items")
class OccurrenceItem(
    @Column(name = "occurrence_id", nullable = false)
    var occurrenceId: UUID,

    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(nullable = false, length = 500)
    var title: String,

    @Column(nullable = false)
    var position: Int,

    @Column
    var notes: String? = null,

    /** The template item it was copied from (null if that was deleted since). */
    @Column(name = "template_item_id")
    var templateItemId: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    /** Whoever ticked it, which may be someone helping. It still counts for the assignees. */
    @Column(name = "completed_by")
    var completedBy: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    /** Optimistic locking: an update based on a stale copy fails instead of silently overwriting. */
    @Version
    @Column(nullable = false)
    var version: Long = 0
}

@Embeddable
data class OccurrenceItemAssigneeId(
    @Column(name = "occurrence_item_id", nullable = false)
    var occurrenceItemId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

@Entity
@Table(name = "occurrence_item_assignees")
class OccurrenceItemAssignee(
    @EmbeddedId
    var id: OccurrenceItemAssigneeId,

    /** Must match the item's checklist; the DB rejects assignees who aren't members. */
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,
)

@Embeddable
data class OccurrenceMemberResultId(
    @Column(name = "occurrence_id", nullable = false)
    var occurrenceId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

/**
 * How one person did in one occurrence. Only people with items get a row; no row means
 * the day is skipped for their streak. Updated live as items are ticked, settled at close.
 */
@Entity
@Table(name = "occurrence_member_results")
class OccurrenceMemberResult(
    @EmbeddedId
    var id: OccurrenceMemberResultId,

    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "assigned_count", nullable = false)
    var assignedCount: Int,
) {
    @Column(name = "completed_count", nullable = false)
    var completedCount: Int = 0

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "stars_awarded", nullable = false)
    var starsAwarded: Int = 0

    @Column(name = "team_bonus_awarded", nullable = false)
    var teamBonusAwarded: Int = 0

    @Column(name = "streak_after", nullable = false)
    var streakAfter: Int = 0

    val isComplete: Boolean get() = completedCount >= assignedCount
}
