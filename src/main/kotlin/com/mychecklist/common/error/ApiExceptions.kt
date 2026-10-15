package com.mychecklist.common.error

/**
 * Exceptions that services throw to produce a specific HTTP status.
 * ApiExceptionHandler turns them into RFC 7807 "problem detail" JSON responses.
 * Messages are shown to API clients, so never put internal details in them.
 */
sealed class ApiException(message: String) : RuntimeException(message)

/** 404. Also used when the caller isn't allowed to know the thing exists. */
class NotFoundException(message: String = "Not found") : ApiException(message)

/** 403. The caller can see the resource but their role doesn't allow this action. */
class ForbiddenException(message: String = "You don't have permission to do that") : ApiException(message)

/** 409. The request clashes with the current state (archived list, duplicate, stale version...). */
class ConflictException(message: String) : ApiException(message)

/** 400. The request is well-formed JSON but asks for something invalid. */
class BadRequestException(message: String) : ApiException(message)
