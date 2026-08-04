package com.bf1.admin.tool.data.remote

/**
 * Gateway JSON-RPC 错误包装，对应 CardTool.js 的 GatewayError。
 *
 * message 为按 code/message/method 映射出的中文可读文案；code/rawMessage/method 保留原始值。
 */
class GatewayError(
    val code: Int?,
    val rawMessage: String?,
    val method: String?
) : Exception(friendlyMessage(code, rawMessage, method)) {

    companion object {
        /** 与 CardTool.js formatGatewayErrorMessage 一致的中文文案映射。 */
        fun friendlyMessage(code: Int?, rawMessage: String?, method: String?): String {
            val message = rawMessage.orEmpty()
            val fromCode = when (code) {
                -32501 -> "Session失效"
                -32504 -> "网络连接超时(后端)"
                -34501 -> "找不到服务器"
                -34504 -> "连接超时(后端)"
                -32601 -> "方法不存在"
                -32602 -> when {
                    message.contains("malformed") -> "请求参数格式错误"
                    message.contains("missing") -> "请求缺少参数"
                    message.contains("method expected session") -> "Session失效"
                    else -> "请求参数错误"
                }
                -35150 -> if (method == "Platoons.getPlatoon") "战队不存在" else null
                -35160 -> "无权限进行此操作"
                -32851 -> "服务器不存在/已过期"
                -32856 -> "玩家不存在"
                -32857 -> "无法处置管理员"
                -32603 -> internalErrorMessage(method, message)
                else -> null
            }
            if (fromCode != null) return fromCode

            // 消息级兜底（CardTool 的后续 if 链）
            return when {
                message == "ServerNotRestartableException" -> "服务器未开启"
                message == "RspErrServerBanMax()" -> "服务器Ban已满"
                message == "RspErrServerVipMax()" -> "服务器VIP已满"
                message == "InvalidLevelIndexException" -> "地图编号无效"
                message == "RspErrUserIsAlreadyVip()" -> "玩家已经是VIP了"
                message == "InvalidServerIdException" -> "服务器ID不存在"
                else -> "未知的接口错误"
            }
        }

        private fun internalErrorMessage(method: String?, message: String): String {
            when (method) {
                "RSP.chooseLevel" -> return "账号不是管理员"
                "RSP.kickPlayer" -> return "无法踢出管理员/机器人不是管理员"
                "RSP.getServerDetails" -> return "机器人不是管理员"
                "Authentication.getEnvIdViaAuthCode" -> return "登录失败"
            }
            return when {
                message == "Internal Error: java.lang.NumberFormatException" -> "数字格式化错误"
                message == "Internal Error: org.apache.thrift.TApplicationException" -> "无权限进行此操作"
                message == "Internal Error: java.lang.IllegalArgumentException" -> "非法的参数"
                message == "Internal Error: java.lang.NullPointerException" -> "空指针"
                message == "Authentication failed" -> "验证失败"
                message.contains("ERR_AUTHENTICATION_REQUIRED") -> "无权限进行此操作"
                message.contains("Error: InvalidServerNameException") -> "服务器名无效"
                message.contains("com.fasterxml.jackson.core.JsonParseException") -> "JSON解析失败"
                message.contains("RspErrInvalidMapRotationId()") -> "地图组不存在"
                message.contains("errorName: ERR_SYSTEM") -> "系统错误"
                message.contains("java") -> "未知服务端错误(java)"
                message.contains("apache") -> "未知服务端错误(apache)"
                message.contains("Timeout") -> "blaze超时"
                message.contains("WalBlazeError") || message.contains("BlazeErrorException") -> "未知服务端错误(blaze)"
                else -> "未知服务端错误"
            }
        }
    }
}
