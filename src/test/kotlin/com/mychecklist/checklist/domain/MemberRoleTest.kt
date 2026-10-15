package com.mychecklist.checklist.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberRoleTest {

    @Test
    fun `roles are ordered viewer then editor then owner`() {
        assertTrue(MemberRole.OWNER.atLeast(MemberRole.OWNER))
        assertTrue(MemberRole.OWNER.atLeast(MemberRole.EDITOR))
        assertTrue(MemberRole.OWNER.atLeast(MemberRole.VIEWER))

        assertFalse(MemberRole.EDITOR.atLeast(MemberRole.OWNER))
        assertTrue(MemberRole.EDITOR.atLeast(MemberRole.EDITOR))
        assertTrue(MemberRole.EDITOR.atLeast(MemberRole.VIEWER))

        assertFalse(MemberRole.VIEWER.atLeast(MemberRole.OWNER))
        assertFalse(MemberRole.VIEWER.atLeast(MemberRole.EDITOR))
        assertTrue(MemberRole.VIEWER.atLeast(MemberRole.VIEWER))
    }
}
