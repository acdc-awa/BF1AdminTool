package com.bf1.admin.tool.cardtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 卡服领域模型/常量表与 CardTool.js 对照测试。 */
class CardToolModelsTest {

    @Test
    fun modesTableMatchesCardTool() {
        assertEquals(
            mapOf(
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
            ),
            MODES
        )
    }

    @Test
    fun configModeNameResolvesThroughTables() {
        val config = CardToolConfig(gameId = "11295245190321", mode = 0x2)
        assertEquals("BreakthroughLarge", config.modeName)
        assertEquals("行动模式", config.modePrettyName)
        assertNull(CardToolConfig(gameId = "x", mode = 0xff).modeName)
    }

    @Test
    fun mapByPlayersHasDefaults() {
        assertEquals(MapEntry("CQ0", "MP_Alps"), MAP_BY_PLAYERS.getValue(0x40))
        assertEquals(MapEntry("AA0", "MP_Naval"), MAP_BY_PLAYERS.getValue(0x28))
        assertEquals(MapEntry("TOW0", "MP_Naval"), MAP_BY_PLAYERS.getValue(0x20))
        assertEquals(MapEntry("TDM0", "MP_Naval"), MAP_BY_PLAYERS.getValue(0x18))
    }

    @Test
    fun extractPinnedMapNameHandlesPathAndUnderscores() {
        // CardTool：mapImage.split("/").pop().split("_").slice(0,2).join("_")
        // 注意：查询串不会剥离（与 CardTool 一致）；真实 rotation 的 mapImage 不含查询串
        assertEquals("MP_Alps?x=1", extractPinnedMapName("level://MP_Alps?x=1"))
        assertEquals("MP_Alps", extractPinnedMapName("MP_Alps"))
        assertEquals("MP_Alps", extractPinnedMapName("/path/to/MP_Alps_CQ0_extra"))
        assertEquals("MP_Alps", extractPinnedMapName("MP_Alps_CQ0"))
        assertEquals("MP_ShovelTown", extractPinnedMapName("MP_Shoveltown"))
        assertEquals("MP_ShovelTown", extractPinnedMapName("/a/b/MP_Shoveltown_CQ0"))
    }

    @Test
    fun buildPinnedRotationUsesAA0ExceptAirAssault() {
        val rotation = listOf(
            MapRotationEntry("level://MP_Alps", "Alps", "征服"),
            MapRotationEntry("level://MP_Shoveltown_CQ0", "Shovel", "征服")
        )
        assertEquals(
            listOf(
                mapOf("gameMode" to "AA0", "mapName" to "MP_Alps"),
                mapOf("gameMode" to "AA0", "mapName" to "MP_ShovelTown")
            ),
            buildPinnedRotation(rotation, mode = 0x1)
        )
        // AirAssault 用 TOW0
        assertEquals(
            listOf(mapOf("gameMode" to "TOW0", "mapName" to "MP_Alps")),
            buildPinnedRotation(listOf(MapRotationEntry("MP_Alps", "Alps", "空中突击")), mode = 0x9)
        )
    }

    @Test
    fun serverUpdatePayloadMatchesCardToolShape() {
        val payload = buildServerUpdatePayload(
            serverId = "86660000",
            name = "My Server",
            description = "desc",
            message = "1700000000000 CardTool",
            password = "pw",
            customGameSettings = mapOf("k" to "v"),
            playerLimit = 0x40
        )
        assertEquals(mapOf("machash" to "1"), payload["deviceIdMap"])
        assertEquals("86660000", payload["serverId"])
        assertEquals(mapOf("bannerUrl" to "", "clearBanner" to true), payload["bannerSettings"])

        val mapRotation = payload["mapRotation"] as Map<*, *>
        val expectedMap = mapOf("gameMode" to "CQ0", "mapName" to "MP_Alps")
        assertEquals(listOf(expectedMap, expectedMap, expectedMap), mapRotation["maps"])
        assertEquals("", mapRotation["rotationType"])
        assertEquals("32", mapRotation["mod"])
        assertEquals("0", mapRotation["name"])
        assertEquals("", mapRotation["description"])
        assertEquals("100", mapRotation["id"])

        val settings = payload["serverSettings"] as Map<*, *>
        assertEquals("My Server", settings["name"])
        assertEquals("desc", settings["description"])
        assertEquals("1700000000000 CardTool", settings["message"])
        assertEquals("pw", settings["password"])
        assertEquals("", settings["bannerUrl"])
        assertEquals("100", settings["mapRotationId"])
        assertEquals(mapOf("k" to "v"), settings["customGameSettings"])
    }

    @Test
    fun serverUpdatePayloadFallsBackTo64PlayerMapForUnknownPlayerCount() {
        val payload = buildServerUpdatePayload(
            serverId = "1", name = "n", description = "", message = "m",
            password = "", customGameSettings = null, playerLimit = 0x21
        )
        val mapRotation = payload["mapRotation"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val maps = mapRotation["maps"] as List<Map<String, String>>
        assertEquals(3, maps.size)
        maps.forEach { assertEquals("MP_Alps", it["mapName"]) }
    }

    @Test
    fun emptyCustomGameSettingsIsPassedThrough() {
        val payload = buildServerUpdatePayload(
            serverId = "1", name = "n", description = "", message = "m",
            password = "", customGameSettings = null, playerLimit = 0x40
        )
        val settings = payload["serverSettings"] as Map<*, *>
        assertEquals(emptyMap<String, Any?>(), settings["customGameSettings"])
    }
}
