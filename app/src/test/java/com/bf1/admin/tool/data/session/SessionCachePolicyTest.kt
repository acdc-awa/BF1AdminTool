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

    /**
     * 6–12h 这段窗口是两个阈值的分工所在：读路径直接命中缓存（不阻塞用户），
     * 同时后台任务会去续期。此前读路径额外要求 !isSessionRefreshDue，
     * 这段窗口永远走不到，12h 的上限形同虚设。
     */
    @Test
    fun sessionBetweenSixAndTwelveHoursIsServedFromCacheAndStillScheduledForRenewal() {
        val nineHours = 9 * 60 * 60 * 1000L

        assertTrue(isSessionUsable(refreshedAt = 0L, now = nineHours))
        assertTrue(isSessionRefreshDue(refreshedAt = 0L, now = nineHours))
    }

    /** 设备时钟回拨时不应继续沿用旧 session —— 判不出年龄就当它已失效。 */
    @Test
    fun clockMovingBackwardsInvalidatesTheCachedSession() {
        assertFalse(isSessionUsable(refreshedAt = 1_000L, now = 999L))
        assertTrue(isSessionRefreshDue(refreshedAt = 1_000L, now = 999L))
    }
}
