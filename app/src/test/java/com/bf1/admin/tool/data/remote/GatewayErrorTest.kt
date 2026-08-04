package com.bf1.admin.tool.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** GatewayError 中文文案映射与 CardTool.js formatGatewayErrorMessage 对照。 */
class GatewayErrorTest {

    @Test
    fun sessionAndTimeoutCodes() {
        assertEquals("Session失效", GatewayError.friendlyMessage(-32501, null, null))
        assertEquals("网络连接超时(后端)", GatewayError.friendlyMessage(-32504, null, null))
        assertEquals("连接超时(后端)", GatewayError.friendlyMessage(-34504, null, null))
    }

    @Test
    fun serverNotFoundAndPermissions() {
        assertEquals("找不到服务器", GatewayError.friendlyMessage(-34501, null, null))
        assertEquals("服务器不存在/已过期", GatewayError.friendlyMessage(-32851, null, null))
        assertEquals("无权限进行此操作", GatewayError.friendlyMessage(-35160, null, null))
    }

    @Test
    fun invalidParamsClassifiedByMessage() {
        assertEquals("请求参数格式错误", GatewayError.friendlyMessage(-32602, "malformed request", null))
        assertEquals("请求缺少参数", GatewayError.friendlyMessage(-32602, "missing params", null))
        assertEquals("Session失效", GatewayError.friendlyMessage(-32602, "method expected session", null))
        assertEquals("请求参数错误", GatewayError.friendlyMessage(-32602, "something else", null))
    }

    @Test
    fun internalErrorByMethod() {
        assertEquals("账号不是管理员", GatewayError.friendlyMessage(-32603, "x", "RSP.chooseLevel"))
        assertEquals("无法踢出管理员/机器人不是管理员", GatewayError.friendlyMessage(-32603, "x", "RSP.kickPlayer"))
        assertEquals("机器人不是管理员", GatewayError.friendlyMessage(-32603, "x", "RSP.getServerDetails"))
        assertEquals("登录失败", GatewayError.friendlyMessage(-32603, "x", "Authentication.getEnvIdViaAuthCode"))
    }

    @Test
    fun internalErrorByMessagePattern() {
        assertEquals("验证失败", GatewayError.friendlyMessage(-32603, "Authentication failed", "RSP.updateServer"))
        assertEquals(
            "无权限进行此操作",
            GatewayError.friendlyMessage(-32603, "blah ERR_AUTHENTICATION_REQUIRED blah", "RSP.updateServer")
        )
        assertEquals("地图组不存在", GatewayError.friendlyMessage(-32603, "RspErrInvalidMapRotationId()", "RSP.updateServer"))
        assertEquals("系统错误", GatewayError.friendlyMessage(-32603, "errorName: ERR_SYSTEM", "RSP.updateServer"))
        assertEquals("未知服务端错误(java)", GatewayError.friendlyMessage(-32603, "java stacktrace", "RSP.updateServer"))
        assertEquals("未知服务端错误", GatewayError.friendlyMessage(-32603, "mystery", "RSP.updateServer"))
    }

    @Test
    fun messageLevelFallbacks() {
        assertEquals("服务器未开启", GatewayError.friendlyMessage(null, "ServerNotRestartableException", null))
        assertEquals("服务器Ban已满", GatewayError.friendlyMessage(null, "RspErrServerBanMax()", null))
        assertEquals("地图编号无效", GatewayError.friendlyMessage(null, "InvalidLevelIndexException", null))
        assertEquals("服务器ID不存在", GatewayError.friendlyMessage(null, "InvalidServerIdException", null))
        assertEquals("未知的接口错误", GatewayError.friendlyMessage(null, "totally unknown", null))
        assertEquals("未知的接口错误", GatewayError.friendlyMessage(999, null, null))
    }

    @Test
    fun exceptionMessageIsFriendly() {
        val e = GatewayError(-34501, null, "GameServer.getFullServerDetails")
        assertEquals("找不到服务器", e.message)
        assertEquals(-34501, e.code)
        assertEquals("GameServer.getFullServerDetails", e.method)
    }
}
