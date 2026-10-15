package com.mychecklist.admin.api

import com.mychecklist.user.CurrentUser
import com.mychecklist.user.UserService
import com.mychecklist.user.api.UserResponse
import com.mychecklist.user.data.UserRepository
import com.mychecklist.user.domain.User
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Super-admin-only endpoints. Every method must call requireSuperAdmin first. */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val userService: UserService,
    private val users: UserRepository,
) {

    @GetMapping("/users")
    fun listUsers(@CurrentUser user: User): List<UserResponse> {
        userService.requireSuperAdmin(user)
        return users.findAll(Sort.by("createdAt")).map(UserResponse::from)
    }
}
