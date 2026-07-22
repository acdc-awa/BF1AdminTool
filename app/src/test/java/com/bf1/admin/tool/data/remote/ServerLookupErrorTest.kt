package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerLookupErrorTest {
    @Test
    fun http500ExplainsThatTheServerIdIsInvalidOrNotOwned() {
        assertEquals(
            "无法查询该服务器：请确认 ServerID 正确，且当前 EA 账号拥有该服务器的服主权限。",
            serverLookupErrorMessage(500)
        )
    }

    @Test
    fun http422ExplainsThatTheServerIdIsInvalidOrNotOwned() {
        assertEquals(
            "无法查询该服务器：请确认 ServerID 正确，且当前 EA 账号拥有该服务器的服主权限。",
            serverLookupErrorMessage(422)
        )
    }

    @Test
    fun nonPermissionHttpErrorsRetainTheirStatusCode() {
        assertEquals("服务器查询失败（HTTP 503）", serverLookupErrorMessage(503))
    }
}
