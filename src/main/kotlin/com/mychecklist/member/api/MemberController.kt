package com.mychecklist.member.api

import com.mychecklist.member.MemberService
import com.mychecklist.user.CurrentUser
import com.mychecklist.user.domain.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** A list's members. Permission checks and rules live in MemberService / ChecklistAccessService. */
@RestController
@RequestMapping("/api/checklists/{id}/members")
class MemberController(private val service: MemberService) {

    /** Viewers and up. The owner first, then by join date. Emails only for the owner and super admins. */
    @GetMapping
    fun list(@CurrentUser user: User, @PathVariable id: UUID): List<MemberResponse> =
        service.list(user, id)
}
