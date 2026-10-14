package com.mychecklist.common

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns exceptions into consistent JSON error bodies (RFC 7807), for example:
 *   { "status": 404, "title": "Not Found", "detail": "Checklist not found", "instance": "/api/..." }
 *
 * Security rule: responses never contain stack traces, SQL, or class names.
 * Unexpected errors are logged on the server and the client gets a generic 500.
 *
 * Standard Spring MVC errors (malformed JSON, wrong HTTP method, missing parameter...)
 * are handled by Spring Boot's built-in problem-details handler, which is enabled
 * in application.yml.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException): ProblemDetail {
        val status = when (ex) {
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is ConflictException -> HttpStatus.CONFLICT
            is BadRequestException -> HttpStatus.BAD_REQUEST
        }
        return ProblemDetail.forStatusAndDetail(status, ex.message ?: status.reasonPhrase)
    }

    /** Bean Validation failures on @Valid request bodies: list each bad field. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request has invalid fields")
        problem.setProperty(
            "errors",
            ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
        )
        return problem
    }

    /** Someone else changed the same row first (JPA @Version). */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleStale(ex: OptimisticLockingFailureException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This was changed by someone else. Reload and try again.")

    /** A database constraint rejected the change. Don't leak the constraint/SQL text. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException): ProblemDetail {
        log.info("Constraint violation: {}", ex.mostSpecificCause.message)
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data")
    }
}
