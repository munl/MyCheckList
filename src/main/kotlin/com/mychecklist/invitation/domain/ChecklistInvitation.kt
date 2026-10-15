package com.mychecklist.invitation.domain

import com.mychecklist.checklist.domain.MemberRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

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
