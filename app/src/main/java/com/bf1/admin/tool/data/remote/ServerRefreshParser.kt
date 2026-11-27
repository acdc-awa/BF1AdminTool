package com.bf1.admin.tool.data.remote

import org.json.JSONObject

/**
 * RSP.getServerDetails 中与服务器身份相关的字段：
 * serverId 永久不变，persistedGameId (UUID) 用于在 searchServers 结果中精确匹配 14 位 gameId。
 */
data class RspServerIdentity(
    val serverId: String,
    val persistedGameId: String,
    val serverName: String
)

/** GameServer.searchServers 返回列表中的单条服务器记录。 */
data class SearchServerEntry(
    val gameId: String,
    val guid: String,
    val name: String
)

/** 解析 RSP.getServerDetails 的 result 节点。 */
internal fun parseRspServerIdentity(result: JSONObject): RspServerIdentity {
    val server = result.optJSONObject("server")
    val settings = result.optJSONObject("serverSettings")
    val serverName = settings?.optString("name").orEmpty()
        .ifBlank { server?.optString("name").orEmpty() }
    return RspServerIdentity(
        serverId = server?.optString("serverId").orEmpty(),
        persistedGameId = server?.optString("persistedGameId").orEmpty(),
        serverName = serverName
    )
}

/** 解析 GameServer.searchServers 的 result 节点。 */
internal fun parseSearchServersResult(result: JSONObject): List<SearchServerEntry> {
    val array = result.optJSONArray("gameservers") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val obj = array.optJSONObject(i) ?: return@mapNotNull null
        SearchServerEntry(
            gameId = obj.optString("gameId").orEmpty(),
            guid = obj.optString("guid").orEmpty(),
            name = obj.optString("name").orEmpty()
        )
    }
}

/** 按 persistedGameId (UUID) 精确匹配 searchServers 返回的服务器条目。 */
internal fun findMatchingServer(
    entries: List<SearchServerEntry>,
    persistedGameId: String
): SearchServerEntry? {
    if (persistedGameId.isBlank()) return null
    return entries.firstOrNull { entry ->
        entry.gameId.isNotBlank() && entry.guid.equals(persistedGameId, ignoreCase = true)
    }
}
