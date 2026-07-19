package com.bf1.admin.tool.data.remote

import java.util.UUID

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Map<String, Any>,
    val id: String = UUID.randomUUID().toString()
)

data class JsonRpcResponse(
    val jsonrpc: String?,
    val result: Map<String, Any>?,
    val error: JsonRpcError?,
    val id: String?
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any?
)
