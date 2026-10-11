package com.bf1.admin.tool.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关 result 类型都不代表失败：RSP.updateServer 回字符串 "success"，
 * 个别查询在服务端状态切换时回 null。这些响应曾因 getJSONObject("result")
 * 强转抛 JSONException，被误判成「updateServer 失败」并让主循环崩到重连。
 */
class GatewayResultTest {

    @Test
    fun objectResultIsReturnedAsIs() {
        val result = gatewayResult(JSONObject("""{"result":{"serverId":"12345678"}}"""))
        assertEquals("12345678", result.getString("serverId"))
    }

    @Test
    fun stringSuccessResultIsNotAnError() {
        // RSP.updateServer 的真实响应：{"result":"success"}
        val result = gatewayResult(JSONObject("""{"result":"success"}"""))
        assertTrue(result.length() == 0)
    }

    @Test
    fun nullResultIsNotAnError() {
        val result = gatewayResult(JSONObject("""{"jsonrpc":"2.0","result":null}"""))
        assertTrue(result.length() == 0)
    }

    @Test
    fun missingResultIsNotAnError() {
        val result = gatewayResult(JSONObject("""{"jsonrpc":"2.0"}"""))
        assertTrue(result.length() == 0)
    }

    @Test
    fun arrayResultIsNotAnError() {
        val result = gatewayResult(JSONObject("""{"result":[1,2,3]}"""))
        assertTrue(result.length() == 0)
    }
}
