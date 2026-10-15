package com.mychecklist.checklist.domain

/** Roles inside one checklist. `rank` orders them: VIEWER < EDITOR < OWNER. */
enum class MemberRole(val rank: Int) {
    VIEWER(1),
    EDITOR(2),
    OWNER(3),
    ;

    fun atLeast(minimum: MemberRole): Boolean = rank >= minimum.rank
}
