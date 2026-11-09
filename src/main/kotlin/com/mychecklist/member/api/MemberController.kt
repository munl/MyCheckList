package com.mychecklist.member.api

import com.mychecklist.member.MemberService
import com.mychecklist.user.CurrentUser
import com.mychecklist.user.domain.User
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** A list's members. Permission checks and rules live in MemberService / ChecklistAccessService. */
@RestController
@RequestMapping("/api/checklists/{id}")
class MemberController(private val service: MemberService) {

    /** Viewers and up. The owner first, then by join date. Emails only for the owner and super admins. */
    @GetMapping("/members")
    fun list(@CurrentUser user: User, @PathVariable id: UUID): List<MemberResponse> =
        service.list(user, id)

    /** Owner only. Switches a member between EDITOR and VIEWER; returns the updated member. */
    @PatchMapping("/members/{userId}")
    fun changeRole(
        @CurrentUser user: User,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody body: ChangeRoleRequest,
    ): MemberResponse = service.changeRole(user, id, userId, body.role!!)

    /** Owner only. Removes an editor or viewer (soft: they keep their stars if they rejoin). */
    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@CurrentUser user: User, @PathVariable id: UUID, @PathVariable userId: UUID) =
        service.remove(user, id, userId)

    /** Editors and viewers leave a list, archived or not. The owner must transfer ownership first. */
    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@CurrentUser user: User, @PathVariable id: UUID) =
        service.leave(user, id)
}
