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
import java.time.Instant
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

/** V1 only supports ANY. EACH (every assignee checks off their own copy) can come later. */
enum class CompletionMode { ANY }

@Entity
@Table(name = "checklists")
class Checklist(
    @Column(nullable = false, length = 200)
    var title: String,

    @Column
    var description: String? = null,

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

    @Column(name = "due_at")
    var dueAt: Instant? = null,

    @Column(name = "created_by")
    var createdBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_mode", nullable = false, length = 10)
    var completionMode: CompletionMode = CompletionMode.ANY

    @Column(name = "completed_at")
    var completedAt: Instant? = null

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
data class ItemAssigneeId(
    @Column(name = "item_id", nullable = false)
    var itemId: UUID,

    @Column(name = "user_id", nullable = false)
    var userId: UUID,
) : Serializable

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
