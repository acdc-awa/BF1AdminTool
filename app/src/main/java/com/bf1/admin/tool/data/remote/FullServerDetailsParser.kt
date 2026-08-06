package com.bf1.admin.tool.data.remote

import com.bf1.admin.tool.cardtool.RspAdmin
import com.bf1.admin.tool.cardtool.RspInfo
import com.bf1.admin.tool.cardtool.RspServerSettings
import org.json.JSONObject

/**
 * GameServer.getFullServerDetails 响应解析（实测结构，见
 * D:\战地1管理工具\杂项\GameServer.getFullServerDetails.txt）：
 *
 * result.serverInfo  → gameId / guid / rotation / slots / mapMode ...
 * result.rspInfo     → server.serverId / serverSettings.* / owner.* / adminList ...
 */
internal fun parseFullServerDetails(result: JSONObject): RspInfo {
    val serverInfo = result.optJSONObject("serverInfo") ?: JSONObject()
    val rspInfo = result.optJSONObject("rspInfo") ?: JSONObject()
    val server = rspInfo.optJSONObject("server") ?: JSONObject()
    val settings = rspInfo.optJSONObject("serverSettings") ?: JSONObject()
    val owner = rspInfo.optJSONObject("owner") ?: JSONObject()

    val adminList = rspInfo.optJSONArray("adminList")
    val admins = if (adminList != null) {
        (0 until adminList.length()).map { i ->
            val a = adminList.getJSONObject(i)
            RspAdmin(
                personaId = jsonOptString(a, "personaId").orEmpty(),
                displayName = a.optString("displayName")
            )
        }
    } else {
        emptyList()
    }

    return RspInfo(
        serverId = server.optString("serverId"),
        gameId = serverInfo.optString("gameId"),
        persistedGameId = serverInfo.optString("guid").ifEmpty { server.optString("persistedGameId") },
        serverSettings = RspServerSettings(
            name = settings.optString("name"),
            description = settings.optString("description"),
            message = settings.optString("message"),
            password = settings.optString("password"),
            mapRotationId = settings.optString("mapRotationId"),
            bannerUrl = settings.optString("bannerUrl"),
            customGameSettings = settings.optString("customGameSettings", "").takeIf { it.isNotEmpty() }
        ),
        adminList = admins,
        ownerPersonaId = jsonOptString(owner, "personaId").orEmpty(),
        ownerDisplayName = owner.optString("displayName")
    )
}
