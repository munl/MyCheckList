package com.mychecklist.support

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/** Put `app.super-admin-emails=$SUPER_ADMIN_EMAIL` in a test's `@SpringBootTest` properties to use [superAdmin]. */
const val SUPER_ADMIN_EMAIL = "boss@example.com"

/**
 * A signed-in test user, identified by a fake Firebase token. `jwt()` stands in for a
 * verified token and goes through the real Spring Security filter chain. The default
 * email is random, so each call is a fresh user.
 */
fun signedIn(
    name: String,
    email: String = "$name-${UUID.randomUUID()}@example.com",
    uid: String = "uid-$email",
): RequestPostProcessor =
    jwt().jwt {
        it.subject(uid)
            .claim("email", email)
            .claim("email_verified", true)
            .claim("name", name)
    }

/** The super admin (same uid as SchemaAndUserSyncTest, in case they share a database). */
val superAdmin: RequestPostProcessor = signedIn("boss", email = SUPER_ADMIN_EMAIL, uid = "uid-boss")
