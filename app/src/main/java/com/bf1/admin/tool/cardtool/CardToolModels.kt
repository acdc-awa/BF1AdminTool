package com.bf1.admin.tool.cardtool

/**
 * 卡服领域模型与常量表。
 *
 * 数值与 .references/CardTool-预热/CardTool.js 完全一致（modes / modePrettyName / map 表、
 * serverUpdatePayload 结构、地图名提取逻辑）。本文件为纯 JVM，可在单元测试中直接验证。
 */

/** BF1 游戏模式编号 → Blaze/服务器侧模式名（CardTool `modes` 表）。 */
val MODES: Map<Int, String> = mapOf(
    0x1 to "Conquest",
    0x2 to "BreakthroughLarge",
    0x3 to "TugOfWar",
    0x4 to "TeamDeathMatch",
    0x5 to "Possession",
    0x6 to "Domination",
    0x7 to "Rush",
    0x8 to "ZoneControl",
    0x9 to "AirAssault",
    0xa to "Breakthrough"
)

/** 模式名 → 中文名（CardTool `modePrettyName` 表）。 */
val MODE_PRETTY_NAMES: Map<String, String> = mapOf(
    "BreakthroughLarge" to "行动模式",
    "Breakthrough" to "闪击行动",
    "Conquest" to "征服",
    "TugOfWar" to "前线",
    "TeamDeathMatch" to "团队死斗",
    "Possession" to "战争信鸽",
    "Domination" to "抢攻",
    "Rush" to "突袭",
    "ZoneControl" to "空降补给",
    "AirAssault" to "空中突击"
)

/** 初始轮换地图条目（CardTool `map` 表的 {gameMode, mapName}）。 */
data class MapEntry(val gameMode: String, val mapName: String)

/** 人数（64/40/32/24）→ 初始轮换地图；0x40=64 人为默认。 */
val MAP_BY_PLAYERS: Map<Int, MapEntry> = mapOf(
    0x40 to MapEntry("CQ0", "MP_Alps"),
    0x28 to MapEntry("AA0", "MP_Naval"),
    0x20 to MapEntry("TOW0", "MP_Naval"),
    0x18 to MapEntry("TDM0", "MP_Naval")
)

/** 进服方式：direct=模拟客户端直连（推荐）；cardtool=原观战占位。 */
enum class JoinStyle { DIRECT, CARDTOOL }

/**
 * 卡服配置（对应 CardTool config.ini）。
 * gameId 为 14 位服务器 GUID（进服/查服用），serverId 为 8 位短 ID（RSP 更新用）。
 */
data class CardToolConfig(
    val gameId: String,
    val mode: Int,
    val player: Int = 0x40,
    val minMap: Int = 1,
    val joinStyle: JoinStyle = JoinStyle.DIRECT,
    val joinTimeoutMs: Long = 12_000,
    val joinPollIntervalMs: Long = 500,
    val primeGids: List<String> = emptyList(),
    val primeRounds: Int = 2,
    val primeStaySeconds: Int = 5
) {
    /** 目标模式名；mode 未定义时返回 null。 */
    val modeName: String? get() = MODES[mode]

    /** 目标模式中文名；mode 未定义时返回 null。 */
    val modePrettyName: String? get() = modeName?.let { MODE_PRETTY_NAMES[it] }
}

/** 服务器轮换条目（来自 GameServer.getServerDetails 的 rotation）。 */
data class MapRotationEntry(
    val mapImage: String,
    val mapPrettyName: String,
    val modePrettyName: String
)

/**
 * 从 mapImage 提取锚定用的地图名，与 CardTool 完全一致：
 * 取最后一个路径段，保留前两个下划线分段，并修正 MP_Shoveltown 拼写。
 */
fun extractPinnedMapName(mapImage: String): String {
    val segment = mapImage.substringAfterLast('/')
    val joined = segment.split('_').take(2).joinToString("_")
    return joined.replace("MP_Shoveltown", "MP_ShovelTown")
}

/** 锚定用的轮换地图（gameMode 固定为 AA0，AirAssault 用 TOW0）。 */
fun buildPinnedRotation(rotation: List<MapRotationEntry>, mode: Int): List<Map<String, String>> {
    val gameMode = if (MODES[mode] == "AirAssault") "TOW0" else "AA0"
    return rotation.map { entry ->
        mapOf("gameMode" to gameMode, "mapName" to extractPinnedMapName(entry.mapImage))
    }
}

/**
 * 构造 RSP.updateServer 的请求体，结构与 CardTool 的 serverUpdatePayload 一致。
 * 返回纯 Map，由网络层转为 JSON。
 */
fun buildServerUpdatePayload(
    serverId: String,
    name: String,
    description: String,
    message: String,
    password: String,
    /** 服务器自定义设置：实测为 JSON 字符串，原样回传；也兼容 Map。 */
    customGameSettings: Any? = null,
    playerLimit: Int,
    mapsOverride: List<Map<String, String>>? = null
): Map<String, Any?> {
    val entry = MAP_BY_PLAYERS[playerLimit] ?: MAP_BY_PLAYERS.getValue(0x40)
    val mapEntry = mapOf("gameMode" to entry.gameMode, "mapName" to entry.mapName)
    val maps: List<Map<String, String>> = mapsOverride ?: listOf(mapEntry, mapEntry, mapEntry)
    return mapOf(
        "deviceIdMap" to mapOf("machash" to "1"),
        "serverId" to serverId,
        "bannerSettings" to mapOf(
            "bannerUrl" to "",
            "clearBanner" to true
        ),
        "mapRotation" to mapOf(
            "maps" to maps,
            "rotationType" to "",
            "mod" to "32",
            "name" to "0",
            "description" to "",
            "id" to "100"
        ),
        "serverSettings" to mapOf(
            "name" to name,
            "description" to description,
            "message" to message,
            "password" to password,
            "bannerUrl" to "",
            "mapRotationId" to "100",
            "customGameSettings" to (customGameSettings ?: emptyMap<String, Any?>())
        )
    )
}

// ═══════════════════════════════════════════════════
// 响应模型（来自 Gateway RPC 的 JSON）
// ═══════════════════════════════════════════════════

data class RspServerSettings(
    val name: String,
    val description: String,
    val message: String,
    val password: String,
    val mapRotationId: String,
    val bannerUrl: String,
    /** 服务器自定义设置，实测响应为 JSON 字符串；RSP.updateServer 原样回传。 */
    val customGameSettings: String?
)

data class RspAdmin(val personaId: String, val displayName: String)

/**
 * GameServer.getFullServerDetails 响应（实测结构）。
 * gameId/guid 在 serverInfo；serverId/owner/adminList/serverSettings 在 rspInfo。
 */
data class RspInfo(
    /** 8 位服务器短 ID（RSP.* 管理操作用）。 */
    val serverId: String,
    /** 14 位服务器 GUID（进服/查服用）。 */
    val gameId: String,
    /** persistedGameId（RSP.chooseLevel 的 persistedGameId 用）。 */
    val persistedGameId: String,
    val serverSettings: RspServerSettings,
    val adminList: List<RspAdmin>,
    val ownerPersonaId: String,
    val ownerDisplayName: String
)

data class ServerSlots(
    val soldierCurrent: Int,
    val soldierMax: Int,
    val spectatorCurrent: Int,
    val spectatorMax: Int
) {
    val occupied: Int get() = soldierCurrent + spectatorCurrent
}

/** GameServer.getServerDetails 的实时服务器状态。 */
data class ServerState(
    val guid: String,
    val mapMode: String,
    val rotation: List<MapRotationEntry>,
    val slots: ServerSlots
)
