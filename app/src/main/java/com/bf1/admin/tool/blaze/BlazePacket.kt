package com.bf1.admin.tool.blaze

/**
 * Blaze 协议错误（来自响应包中的 ERRC 字段）。
 *
 * 与 CardTool.js 的 BlazeError 对应：包含组件名、错误名与描述，
 * message 为 "名称: 描述" 便于直接展示。
 */
class BlazeError(
    val component: String,
    val name: String,
    val description: String?,
    val errc: Long? = null
) : Exception(name) {
    override val message: String
        get() = listOfNotNull(name, description).joinToString(": ")
}

/**
 * 解码后的 Blaze 响应包。
 *
 * [data] 仅在无错误时非空；错误时 [error]/[errc] 非空。
 */
class BlazePacket(
    val method: String,
    val type: Int,
    val id: Int,
    val length: Int,
    val error: BlazeError?,
    val errc: Long?,
    val data: Map<String, Any?>?,
    /** 错误包的原始字节（诊断用），正常包为 null。 */
    val rawBytes: ByteArray? = null
)

/**
 * 待发送的 Blaze 请求。
 *
 * [data] 的 key 遵循 CardTool 的字段命名约定：
 * 前 4 字符为 tag，第 5 字符为空格，第 6 字符起为类型 hex（如 "GID  0"、"PLDL 43"、"PLYA 511"）。
 */
class BlazeRequest(
    val method: String,
    val type: Int = BlazeCodec.TYPE_SEND_COMMAND,
    val data: Map<String, Any?> = emptyMap()
)

/** 连接已关闭（对应 CardTool.js 的 "Connection Closed"）。 */
class BlazeConnectionClosedException(message: String) : Exception(message)
