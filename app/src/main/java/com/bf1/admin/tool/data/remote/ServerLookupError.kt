package com.bf1.admin.tool.data.remote

internal fun serverLookupErrorMessage(httpStatus: Int): String = when (httpStatus) {
    500, 422 -> "无法查询该服务器：请确认 ServerID 正确，且当前 EA 账号拥有该服务器的服主权限。"
    else -> "服务器查询失败（HTTP $httpStatus）"
}

class ServerLookupException(httpStatus: Int) : Exception(serverLookupErrorMessage(httpStatus))
