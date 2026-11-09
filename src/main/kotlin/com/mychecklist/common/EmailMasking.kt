package com.mychecklist.common

/**
 * Hides an email for logs: keeps the first letter and the domain, e.g. `j***@gmail.com`.
 * Email addresses are never logged in full (see "Email privacy" in PROGRESS.md).
 */
fun maskEmail(email: String): String {
    val at = email.lastIndexOf('@')
    if (at <= 0) return "***"
    return "${email.first()}***${email.substring(at)}"
}
