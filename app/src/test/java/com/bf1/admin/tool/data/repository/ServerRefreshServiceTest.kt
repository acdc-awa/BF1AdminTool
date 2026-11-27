package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.cardtool.RspInfo
import com.bf1.admin.tool.cardtool.RspServerSettings
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.RspServerIdentity
import com.bf1.admin.tool.data.remote.SearchServerEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRefreshServiceTest {

    private class MemoryCooldownStore : RefreshCooldownStore {
        private val map = mutableMapOf<String, Long>()
        override fun getLastRefreshedAt(ownerPersonaId: String): Long = map[ownerPersonaId] ?: 0L
        override fun setLastRefreshedAt(ownerPersonaId: String, timestampMs: Long) {
            map[ownerPersonaId] = timestampMs
        }
    }

    private data class PersistedRow(
        val id: Long,
        val serverId: String,
        val serverName: String,
        val gameId: String?,
        val guid: String?
    )

    private fun fakeRspInfo(
        serverId: String,
        gameId: String,
        guid: String,
        name: String
    ) = RspInfo(
        serverId = serverId,
        gameId = gameId,
        persistedGameId = guid,
        serverSettings = RspServerSettings(
            name = name,
            description = "",
            message = "",
            password = "",
            mapRotationId = "",
            bannerUrl = "",
            customGameSettings = null
        ),
        adminList = emptyList(),
        ownerPersonaId = "",
        ownerDisplayName = ""
    )

    @Test
    fun fastPath_validGameIdUpdatesNameAndBackfillsGuidInSingleRpc() = runBlocking {
        val server = ServerEntity(
            id = 1L,
            serverId = "12610359",
            serverName = "Old Name",
            ownerPersonaId = "owner1",
            isActive = true,
            gameId = "11545330210507",
            guid = null // 老数据缺少 guid
        )
        val persisted = mutableListOf<PersistedRow>()
        var rspCalled = false
        var searchCalled = false

        val service = ServerRefreshService(
            loadServers = { listOf(server) },
            persistMetadata = { id, sid, name, gid, guid ->
                persisted.add(PersistedRow(id, sid, name, gid, guid))
            },
            withActiveSession = { block -> block("session-1") },
            fetchFullDetails = { _, gid ->
                fakeRspInfo("12610359", gid, "db29c490-c04a-4535-857e-6b8580f0cf94", "Renamed Fast")
            },
            fetchRspIdentity = { _, _ ->
                rspCalled = true
                error("should not call RSP in fast path")
            },
            searchServersByName = { _, _ ->
                searchCalled = true
                error("should not call searchServers in fast path")
            },
            cooldownStore = MemoryCooldownStore()
        )

        val summary = service.refreshAccountServers("owner1", force = true)
        assertEquals(1, summary.updated)
        assertEquals(0, summary.failed)
        assertFalse(rspCalled)
        assertFalse(searchCalled)
        assertEquals(
            listOf(
                PersistedRow(
                    id = 1L,
                    serverId = "12610359",
                    serverName = "Renamed Fast",
                    gameId = "11545330210507",
                    guid = "db29c490-c04a-4535-857e-6b8580f0cf94"
                )
            ),
            persisted
        )
    }

    @Test
    fun recoveryPath_staleGameIdRecoversViaRspAndGuidSearchAndRespectsCooldown() = runBlocking {
        val server = ServerEntity(
            id = 2L,
            serverId = "12610359",
            serverName = "Old Name",
            ownerPersonaId = "owner1",
            isActive = true,
            gameId = "11111111111111",
            guid = "guid-1"
        )
        val persisted = mutableListOf<PersistedRow>()
        var currentTime = 1_000_000L
        val cooldown = MemoryCooldownStore()

        val service = ServerRefreshService(
            loadServers = { listOf(server) },
            persistMetadata = { id, sid, name, gid, guid ->
                persisted.add(PersistedRow(id, sid, name, gid, guid))
            },
            withActiveSession = { block -> block("session-1") },
            fetchFullDetails = { _, _ -> throw RuntimeException("gameId expired") },
            fetchRspIdentity = { _, sid -> RspServerIdentity(sid, "guid-1", "New Name") },
            searchServersByName = { _, _ ->
                listOf(SearchServerEntry("11545330210507", "guid-1", "New Name"))
            },
            cooldownStore = cooldown,
            nowMs = { currentTime },
            cooldownMs = 30 * 60 * 1000L
        )

        val first = service.refreshAccountServers("owner1", force = false)
        assertFalse(first.skippedByCooldown)
        assertEquals(1, first.updated)
        assertEquals(
            listOf(PersistedRow(2L, "12610359", "New Name", "11545330210507", "guid-1")),
            persisted
        )

        // 10 分钟内自动刷新受冷却跳过
        currentTime += 10 * 60 * 1000L
        assertTrue(service.refreshAccountServers("owner1", force = false).skippedByCooldown)

        // 手动刷新无视冷却
        assertFalse(service.refreshAccountServers("owner1", force = true).skippedByCooldown)
    }

    @Test
    fun recoveryPath_bothGameIdAndServerIdChangedRecoversAllFieldsViaGuid() = runBlocking {
        val server = ServerEntity(
            id = 3L,
            serverId = "old-sid",
            serverName = "[Sakura]Server",
            ownerPersonaId = "owner1",
            gameId = "old-gid",
            guid = "guid-permanent"
        )
        val persisted = mutableListOf<PersistedRow>()

        val service = ServerRefreshService(
            loadServers = { listOf(server) },
            persistMetadata = { id, sid, name, gid, guid ->
                persisted.add(PersistedRow(id, sid, name, gid, guid))
            },
            withActiveSession = { block -> block("session-1") },
            fetchFullDetails = { _, gid ->
                if (gid == "old-gid") throw RuntimeException("stale gameId")
                fakeRspInfo("new-sid", gid, "guid-permanent", "[Sakura]Server")
            },
            fetchRspIdentity = { _, _ -> throw RuntimeException("stale serverId") },
            searchServersByName = { _, _ ->
                listOf(SearchServerEntry("new-gid-14digits", "guid-permanent", "[Sakura]Server"))
            },
            cooldownStore = MemoryCooldownStore()
        )

        val result = service.calibrateForCardTool(server)
        assertTrue(result is ServerRefreshResult.Success)
        val ok = result as ServerRefreshResult.Success
        assertTrue(ok.serverIdChanged)
        assertTrue(ok.gameIdChanged)
        assertEquals("new-sid", ok.server.serverId)
        assertEquals("new-gid-14digits", ok.server.gameId)
        assertEquals(
            listOf(PersistedRow(3L, "new-sid", "[Sakura]Server", "new-gid-14digits", "guid-permanent")),
            persisted
        )
    }

    @Test
    fun fastPath_guidMismatchFallsThroughToGuidRecovery() = runBlocking {
        val server = ServerEntity(
            id = 4L,
            serverId = "12610359",
            serverName = "My Server",
            ownerPersonaId = "owner1",
            gameId = "reused-gid",
            guid = "guid-mine"
        )
        val persisted = mutableListOf<PersistedRow>()

        val service = ServerRefreshService(
            loadServers = { listOf(server) },
            persistMetadata = { id, sid, name, gid, guid ->
                persisted.add(PersistedRow(id, sid, name, gid, guid))
            },
            withActiveSession = { block -> block("session-1") },
            fetchFullDetails = { _, _ ->
                // 模拟旧 gameId 意外指向了另一台不同 guid 的服务器
                fakeRspInfo("99999999", "reused-gid", "guid-other", "Other Server")
            },
            fetchRspIdentity = { _, sid -> RspServerIdentity(sid, "guid-mine", "My Renamed Server") },
            searchServersByName = { _, _ ->
                listOf(SearchServerEntry("my-new-gid", "guid-mine", "My Renamed Server"))
            },
            cooldownStore = MemoryCooldownStore()
        )

        val result = service.calibrateForCardTool(server)
        assertTrue(result is ServerRefreshResult.Success)
        val ok = result as ServerRefreshResult.Success
        assertEquals("my-new-gid", ok.server.gameId)
        assertEquals("My Renamed Server", ok.server.serverName)
        assertEquals(
            listOf(PersistedRow(4L, "12610359", "My Renamed Server", "my-new-gid", "guid-mine")),
            persisted
        )
    }

    @Test
    fun calibrateForCardTool_strictModeFailsWhenOfflineAndCannotMatchGuid() = runBlocking {
        val server = ServerEntity(
            id = 5L,
            serverId = "12610359",
            serverName = "Old Name",
            ownerPersonaId = "owner1",
            gameId = "old-gid",
            guid = "guid-1"
        )
        val persisted = mutableListOf<PersistedRow>()

        val service = ServerRefreshService(
            loadServers = { listOf(server) },
            persistMetadata = { id, sid, name, gid, guid ->
                persisted.add(PersistedRow(id, sid, name, gid, guid))
            },
            withActiveSession = { block -> block("session-1") },
            fetchFullDetails = { _, _ -> throw RuntimeException("offline") },
            fetchRspIdentity = { _, sid -> RspServerIdentity(sid, "guid-1", "New Offline Name") },
            searchServersByName = { _, _ -> emptyList() },
            cooldownStore = MemoryCooldownStore()
        )

        val result = service.calibrateForCardTool(server)
        assertTrue(result is ServerRefreshResult.Failed)
        // 严格模式中止但顺带保存已拿到的最新服名，保留原 gameId
        assertEquals(
            listOf(PersistedRow(5L, "12610359", "New Offline Name", "old-gid", "guid-1")),
            persisted
        )
    }
}
