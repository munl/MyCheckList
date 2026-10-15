package com.mychecklist.stars.domain

enum class StarTransactionType {
    /** Earned by finishing your items in an occurrence (1–4★ depending on streak). */
    DAILY_COMPLETION,

    /** Earned when everyone with items finished (1–3★ depending on team streak). */
    TEAM_BONUS,

    /** Added or removed by an owner/editor. Needs a reason. */
    ADJUSTMENT,

    /** Held when a reward is requested (negative). */
    REDEMPTION,

    /** Given back when a redemption is rejected or cancelled. */
    REDEMPTION_REFUND,
}
