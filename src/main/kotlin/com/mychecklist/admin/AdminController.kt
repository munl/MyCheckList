package com.mychecklist.admin

import com.mychecklist.user.UserRepository
import com.mychecklist.user.UserResponse
import com.mychecklist.user.UserService
import org.springframework.data.domain.Sort
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Super-admin-only endpoints. More (edit/delete anything) get added alongside the checklist APIs. */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val userService: UserService,
    private val users: UserRepository,
) {

    @GetMapping("/users")
    fun listUsers(@AuthenticationPrincipal jwt: Jwt): List<UserResponse> {
        userService.requireSuperAdmin(userService.syncFromToken(jwt))
        return users.findAll(Sort.by("createdAt")).map(UserResponse::from)
    }
}
