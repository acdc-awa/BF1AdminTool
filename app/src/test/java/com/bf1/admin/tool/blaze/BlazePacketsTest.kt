package com.bf1.admin.tool.blaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 直连进服包必须与 bf1_direct_join_stay_forever.py 的
 * build_join_game_packet + patch_direct_join_packet 形状一致：
 * GENT 0=0、JMET 0=1、SLID 0=255、玩家 SLOT 0=1、无 RLST 41。
 */
class BlazePacketsTest {

    private fun packet(gent: Int = 0): Map<String, Any?> = BlazePackets.joinGame(
        gameId = 11295245190321L,
        personaId = 1005613115321L,
        platformId = 1000861387047L,
        connectionGroup = listOf(30722L, 2L, 12L),
        protocolVersion = "3779779",
        role = "soldier",
        gent = gent
    )

    @Test
    fun cmgdAndTopLevelMatchDirectJoin() {
        val p = packet()
        @Suppress("UNCHECKED_CAST")
        val cmgd = p["CMGD 3"] as Map<String, Any?>
        assertEquals(setOf("GGTY 0", "GVER 1", "PNET 62"), cmgd.keys)
        assertEquals("3779779", cmgd["GVER 1"])
        assertEquals(11295245190321L, p["GID  0"])
        assertEquals(1L, p["JMET 0"])
        assertEquals(255L, p["SLID 0"])
    }

    @Test
    fun pljdMatchesDirectJoinAndHasNoRoleList() {
        @Suppress("UNCHECKED_CAST")
        val pljd = packet()["PLJD 3"] as Map<String, Any?>
        assertEquals(
            setOf("BTPL 9", "DFRL 1", "GENT 0", "PLDL 43", "SLOT 0", "TID  0", "TIDX 0"),
            pljd.keys
        )
        assertEquals(0L, pljd["GENT 0"])
        assertEquals(listOf(30722L, 2L, 12L), pljd["BTPL 9"])
        assertEquals(0L, pljd["SLOT 0"])
        assertEquals(65534L, pljd["TID  0"])

        @Suppress("UNCHECKED_CAST")
        val entry = (pljd["PLDL 43"] as List<Map<String, Any?>>).single()
        assertFalse("直连包必须去掉 RLST 41", entry.containsKey("RLST 41"))
        assertEquals("soldier", entry["RLNM 1"])
        assertEquals(1L, entry["SLOT 0"])
        assertEquals(mapOf("latency" to "-1"), entry["PLYA 511"])

        @Suppress("UNCHECKED_CAST")
        val usid = entry["USID 3"] as Map<String, Any?>
        assertEquals(1005613115321L, usid["ID   0"])
        assertEquals(1000861387047L, usid["EXID 0"])
    }

    @Test
    fun gentIsOverridable() {
        @Suppress("UNCHECKED_CAST")
        val pljd = packet(gent = 2)["PLJD 3"] as Map<String, Any?>
        assertEquals(2L, pljd["GENT 0"])
    }
}
