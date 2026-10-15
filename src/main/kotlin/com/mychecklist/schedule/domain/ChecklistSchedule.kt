package com.mychecklist.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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
