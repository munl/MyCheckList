package com.mychecklist.user.api

import com.mychecklist.user.CurrentUser
import com.mychecklist.user.domain.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me")
class MeController {

    /** The app calls this right after Firebase sign-in to create/fetch its user profile. */
    @GetMapping
    fun me(@CurrentUser user: User): UserResponse = UserResponse.from(user)
}
