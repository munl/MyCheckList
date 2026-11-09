package com.mychecklist.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that tests move forward by hand, so "a day later" doesn't mean waiting a day. */
class TestClock(private var now: Instant = Instant.parse("2026-11-08T09:00:00Z")) : Clock() {
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
