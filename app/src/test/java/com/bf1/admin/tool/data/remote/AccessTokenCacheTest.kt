package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTokenCacheTest {
    @Test
    fun cache_from_another_account_is_not_reusable() {
        val cache = AccessTokenCache(
            remid = "account-a",
            token = "token-a",
            refreshedAt = 1_000L
        )

        assertTrue(cache.isReusableFor("account-a", 2_000L))
        assertFalse(cache.isReusableFor("account-b", 2_000L))
    }
}
