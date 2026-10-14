package com.mychecklist.checklist

import com.mychecklist.user.CurrentUser
import com.mychecklist.user.User
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Checklist endpoints. Controllers stay thin: permission checks and rules live in
 * ChecklistService / ChecklistAccessService.
 */
@RestController
@RequestMapping("/api/checklists")
class ChecklistController(private val service: ChecklistService) {

    /** Create a list. The caller becomes its OWNER. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@CurrentUser user: User, @Valid @RequestBody body: CreateChecklistRequest): ChecklistResponse =
        service.create(user, body)

    /** Lists the caller is a member of. `?archived=true` for archived ones. */
    @GetMapping
    fun listMine(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "false") archived: Boolean,
    ): List<ChecklistResponse> = service.listMine(user, archived)

    @GetMapping("/{id}")
    fun get(@CurrentUser user: User, @PathVariable id: UUID): ChecklistResponse =
        service.get(user, id)

    /** Editors and owners. */
    @PatchMapping("/{id}")
    fun update(
        @CurrentUser user: User,
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateChecklistRequest,
    ): ChecklistResponse = service.update(user, id, body)

    /** Owner only. Archived lists are read-only until unarchived. */
    @PostMapping("/{id}/archive")
    fun archive(@CurrentUser user: User, @PathVariable id: UUID): ChecklistResponse =
        service.archive(user, id)

    @PostMapping("/{id}/unarchive")
    fun unarchive(@CurrentUser user: User, @PathVariable id: UUID): ChecklistResponse =
        service.unarchive(user, id)

    /** Owner only. Permanent; requires `?confirm=true`. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @CurrentUser user: User,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "false") confirm: Boolean,
    ) = service.delete(user, id, confirm)
}
