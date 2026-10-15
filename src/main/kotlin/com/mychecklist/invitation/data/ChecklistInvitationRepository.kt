package com.mychecklist.invitation.data

import com.mychecklist.invitation.domain.ChecklistInvitation
import com.mychecklist.invitation.domain.InvitationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChecklistInvitationRepository : JpaRepository<ChecklistInvitation, UUID> {
    /** The invite inbox. */
    fun findByInviteeEmailAndStatusOrderByCreatedAtDesc(
        inviteeEmail: String,
        status: InvitationStatus,
    ): List<ChecklistInvitation>
}
