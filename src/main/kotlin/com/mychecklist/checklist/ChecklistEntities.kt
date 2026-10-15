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
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Foreign keys are mapped as plain UUID fields rather than JPA relationships.
// This keeps queries explicit and avoids lazy-loading surprises; join in repositories as needed.

/** Roles inside one checklist. `rank` orders them: VIEWER < EDITOR < OWNER. */
enum class MemberRole(val rank: Int) {
    VIEWER(1),
    EDITOR(2),
    OWNER(3),
    ;

    fun atLeast(minimum: MemberRole): Boolean = rank >= minimum.rank
}

enum class InvitationStatus { PENDING, ACCEPTED, DECLINED, REVOKED, EXPIRED }

@Entity
@Table(name = "checklists")
class Checklist(
    @Column(nullable = false, length = 200)
    var title: String,

    @Column
    var description: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,

    /** IANA zone (e.g. "America/Vancouver"). Decides when a "day" ends for everyone on the list. */
    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String = "UTC",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Consecutive occurrences where every member with items finished them. */
    @Column(name = "team_current_streak", nullable = false)
    var teamCurrentStreak: Int = 0

    @Column(name = "team_longest_streak", nullable = false)
    var teamLongestStreak: Int = 0

    @Column(name = "team_last_completed_date")
    var teamLastCompletedDate: LocalDate? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Column(name = "archived_at")
    var archivedAt: Instant? = null
}

@Embeddable
data class ChecklistMemberId(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

@Entity
@Table(name = "checklist_members")
class ChecklistMember(
    @EmbeddedId
    var id: ChecklistMemberId,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var role: MemberRole,
) {
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant? = null

    // Cached totals. star_transactions is the source of truth; these are updated in the
    // same transaction as each ledger row so reads stay fast.

    @Column(name = "star_balance", nullable = false)
    var starBalance: Int = 0

    @Column(name = "current_streak", nullable = false)
    var currentStreak: Int = 0

    @Column(name = "longest_streak", nullable = false)
    var longestStreak: Int = 0

    /** occurrence_date of the last occurrence this member finished. */
    @Column(name = "last_completed_date")
    var lastCompletedDate: LocalDate? = null
}

@Entity
@Table(name = "checklist_invitations")
class ChecklistInvitation(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "invitee_email", nullable = false, length = 320)
    var inviteeEmail: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var role: MemberRole,

    @Column(name = "invited_by")
    var invitedBy: UUID? = null,

    @Column(name = "invitee_user_id")
    var inviteeUserId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now().plusSeconds(30L * 24 * 60 * 60),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: InvitationStatus = InvitationStatus.PENDING

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @Column(name = "responded_at")
    var respondedAt: Instant? = null
}

/** A template item. Each occurrence gets its own copy (OccurrenceItem) when it's created. */
@Entity
@Table(name = "checklist_items")
class ChecklistItem(
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(nullable = false, length = 500)
    var title: String,

    @Column(nullable = false)
    var position: Int,

    @Column
    var notes: String? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}

@Embeddable
data class ItemAssigneeId(
    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

/** Default assignee of a template item; copied into each occurrence. */
@Entity
@Table(name = "item_assignees")
class ItemAssignee(
    @EmbeddedId
    var id: ItemAssigneeId,

    /** Must match the item's checklist; the DB rejects assignees who aren't members. */
    @Column(name = "checklist_id", nullable = false)
    var checklistId: UUID,

    @Column(name = "assigned_by")
    var assignedBy: UUID? = null,
) {
    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    var assignedAt: Instant? = null
}
