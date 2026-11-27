package com.bf1.admin.tool.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerRefreshParserTest {

    @Test
    fun parseRspServerIdentity_prefersServerSettingsName() {
        val result = JSONObject(
            """
            {
              "server": {
                "serverId": "12610359",
                "persistedGameId": "db29c490-c04a-4535-857e-6b8580f0cf94",
                "name": "OldServerName"
              },
              "serverSettings": {
                "name": "[Sakura]supernoobonly/lkdkp<1.5/v<150/QQx1067310302"
              }
            }
            """.trimIndent()
        )

        val identity = parseRspServerIdentity(result)
        assertEquals("12610359", identity.serverId)
        assertEquals("db29c490-c04a-4535-857e-6b8580f0cf94", identity.persistedGameId)
        assertEquals("[Sakura]supernoobonly/lkdkp<1.5/v<150/QQx1067310302", identity.serverName)
    }

    @Test
    fun parseRspServerIdentity_fallsBackToServerName() {
        val result = JSONObject(
            """
            {
              "server": {
                "serverId": "12610359",
                "persistedGameId": "db29c490-c04a-4535-857e-6b8580f0cf94",
                "name": "FallbackName"
              },
              "serverSettings": {
                "name": ""
              }
            }
            """.trimIndent()
        )

        val identity = parseRspServerIdentity(result)
        assertEquals("FallbackName", identity.serverName)
    }

    @Test
    fun parseSearchServersResult_andFindMatchingServer_matchesByGuidIgnoringCase() {
        val result = JSONObject(
            """
            {
              "gameservers": [
                {
                  "gameId": "99999999999999",
                  "guid": "00000000-0000-0000-0000-000000000000",
                  "name": "Other Server"
                },
                {
                  "gameId": "11545330210507",
                  "guid": "DB29C490-C04A-4535-857E-6B8580F0CF94",
                  "name": "[Sakura]supernoobonly"
                }
              ]
            }
            """.trimIndent()
        )

        val entries = parseSearchServersResult(result)
        assertEquals(2, entries.size)

        val matched = findMatchingServer(entries, "db29c490-c04a-4535-857e-6b8580f0cf94")
        assertEquals("11545330210507", matched?.gameId)
        assertEquals("[Sakura]supernoobonly", matched?.name)
    }

    @Test
    fun findMatchingServer_returnsNullWhenMissingOrBlankGameId() {
        val entries = listOf(
            SearchServerEntry(gameId = "", guid = "db29c490-c04a-4535-857e-6b8580f0cf94", name = "NoGid")
        )
        assertNull(findMatchingServer(entries, "db29c490-c04a-4535-857e-6b8580f0cf94"))
        assertNull(findMatchingServer(entries, ""))
    }
}
