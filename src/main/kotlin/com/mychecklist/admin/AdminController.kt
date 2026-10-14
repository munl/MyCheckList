package com.mychecklist.admin

import com.mychecklist.user.CurrentUser
import com.mychecklist.user.User
import com.mychecklist.user.UserRepository
import com.mychecklist.user.UserResponse
import com.mychecklist.user.UserService
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
