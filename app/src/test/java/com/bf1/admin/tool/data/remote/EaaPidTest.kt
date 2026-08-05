package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EaaPidTest {

    @Test
    fun insufficientScopeOnlyOn403WithKeyword() {
        assertTrue(isInsufficientScope(403, """{"error":{"message":"insufficient_scope"}}"""))
        assertFalse(isInsufficientScope(200, """{"error":{"message":"insufficient_scope"}}"""))
        assertFalse(isInsufficientScope(403, """{"error":{"code":"unknown"}}"""))
    }
}
