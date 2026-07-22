package com.bf1.admin.tool.data.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCachePolicyTest {
    @Test
    fun cachedSessionRemainsUsableForTwelveHours() {
        assertTrue(isSessionUsable(refreshedAt = 0L, now = 12 * 60 * 60 * 1000L - 1))
        assertFalse(isSessionUsable(refreshedAt = 0L, now = 12 * 60 * 60 * 1000L))
    }

    @Test
    fun backgroundRefreshIsDueEverySixHours() {
        assertFalse(isSessionRefreshDue(refreshedAt = 0L, now = 6 * 60 * 60 * 1000L - 1))
        assertTrue(isSessionRefreshDue(refreshedAt = 0L, now = 6 * 60 * 60 * 1000L))
    }
}
