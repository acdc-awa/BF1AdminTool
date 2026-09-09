package com.bf1.admin.tool.blaze

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * joinGameCardtool 必须与原版 CardTool 3.16 的 joinGame 包逐字段一致
 * （对照 Cardtool-Rebuild/CardTool.optimized.js 的 legacyCardtoolJoinPacket）。
 */
class BlazePacketsTest {

    private fun packet(): Map<String, Any?> = BlazePackets.joinGameCardtool(
        gameId = 11295245190321L,
        personaId = 1005613115321L,
        platformId = 1000861387047L,
        displayName = "GCYYSMG",
        connectionGroup = listOf(30722L, 2L, 12L)
    )

    @Test
    fun cmgdBlockMatchesOriginal() {
        @Suppress("UNCHECKED_CAST")
        val cmgd = packet()["CMGD 3"] as Map<String, Any?>
        assertEquals(setOf("GGTY 0", "GVER 1", "OSID 0", "PNET 62", "SCIO 3"), cmgd.keys)
        assertEquals(0L, cmgd["GGTY 0"])
        assertEquals("3779779", cmgd["GVER 1"])
        assertEquals(0L, cmgd["OSID 0"])

        @Suppress("UNCHECKED_CAST")
        val scio = cmgd["SCIO 3"] as Map<String, Any?>
        assertEquals(setOf("SCEN 1", "SCEV 0", "SCVA 0", "SUBN 1"), scio.keys)

        @Suppress("UNCHECKED_CAST")
        val pnet = cmgd["PNET 62"] as Map<String, Any?>
        assertEquals(BlazePackets.NETWORK_INFO, pnet["VALU 3"])
    }

    @Test
    fun topLevelAndPljdMatchOriginal() {
        val p = packet()
        assertEquals(11295245190321L, p["GID  0"])
        assertEquals(1L, p["JMET 0"])
        assertEquals(255L, p["SLID 0"])

        @Suppress("UNCHECKED_CAST")
        val pljd = p["PLJD 3"] as Map<String, Any?>
        assertEquals(
            setOf("BTPL 9", "DFRL 1", "GENT 0", "PLDL 43", "SLOT 0", "TID  0", "TIDX 0"),
            pljd.keys
        )
        assertEquals(listOf(30722L, 2L, 12L), pljd["BTPL 9"])
        assertEquals(2L, pljd["GENT 0"])
        assertEquals(0L, pljd["SLOT 0"])
        assertEquals(65534L, pljd["TID  0"])
        assertEquals(65535L, pljd["TIDX 0"])
    }

    @Test
    fun pldlEntryMatchesOriginal() {
        @Suppress("UNCHECKED_CAST")
        val pljd = packet()["PLJD 3"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val entry = (pljd["PLDL 43"] as List<Map<String, Any?>>).single()

        assertEquals(setOf("IREP 0", "PLYA 511", "RLNM 1", "USID 3"), entry.keys)
        assertEquals(0L, entry["IREP 0"])
        assertEquals("soldier", entry["RLNM 1"])
        assertEquals(mapOf("latency" to "-1", "premium" to "true", "rank" to "23"), entry["PLYA 511"])

        @Suppress("UNCHECKED_CAST")
        val usid = entry["USID 3"] as Map<String, Any?>
        assertEquals(
            setOf("AID  0", "ALOC 0", "EXBB 2", "EXID 0", "ID   0", "NAME 1", "NASP 1", "ORIG 0", "PIDI 0"),
            usid.keys
        )
        assertEquals(1000861387047L, usid["AID  0"])
        assertEquals(2053652818L, usid["ALOC 0"])
        assertEquals(1000861387047L, usid["EXID 0"])
        assertEquals(1005613115321L, usid["ID   0"])
        assertEquals("GCYYSMG", usid["NAME 1"])
        assertEquals("cem_ea_id", usid["NASP 1"])
    }
}
