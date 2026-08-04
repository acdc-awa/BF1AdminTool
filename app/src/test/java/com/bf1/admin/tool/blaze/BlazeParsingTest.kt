package com.bf1.admin.tool.blaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blaze 响应解析辅助（对应 CardTool connectionGroupIdFromLogin / rolesFromGameInfo / playerInPros）。 */
class BlazeParsingTest {

    @Test
    fun connectionGroupIdFromLoginPrefersTopLevelCgid() {
        val data = mapOf(
            "SESS 3" to mapOf("BUID 0" to 1L),
            "CGID 9" to listOf(30722L, 2L, 12345L)
        )
        assertEquals(12345L, BlazeParsing.connectionGroupIdFromLogin(data))
    }

    @Test
    fun connectionGroupIdFallsBackToSessionCgid() {
        val data = mapOf("SESS 3" to mapOf("CGID 9" to listOf(30722L, 2L, 999L)))
        assertEquals(999L, BlazeParsing.connectionGroupIdFromLogin(data))
    }

    @Test
    fun connectionGroupIdHandlesObjectForm() {
        val data = mapOf("SESS 3" to emptyMap<String, Any?>(), "CGID 9" to mapOf("id" to 42L))
        assertEquals(42L, BlazeParsing.connectionGroupIdFromLogin(data))
    }

    @Test
    fun connectionGroupIdReturnsZeroWhenMissing() {
        assertEquals(0L, BlazeParsing.connectionGroupIdFromLogin(null))
        assertEquals(0L, BlazeParsing.connectionGroupIdFromLogin(mapOf("SESS 3" to emptyMap<String, Any?>())))
    }

    @Test
    fun rolesFromGameInfoReadsCritKeys() {
        val gameInfo = mapOf<String, Any?>(
            "RNFO 3" to mapOf("CRIT 513" to mapOf("soldier" to 1L, "commander" to 1L))
        )
        assertEquals(listOf("soldier", "commander"), BlazeParsing.rolesFromGameInfo(gameInfo))
        assertEquals(emptyList<String>(), BlazeParsing.rolesFromGameInfo(null))
    }

    @Test
    fun playerInProsMatchesByPersonaId() {
        val players = listOf(
            mapOf("PID  0" to 111L, "ROLE 1" to "soldier"),
            mapOf("PID  0" to 222L)
        )
        assertTrue(BlazeParsing.playerInPros(players, 111L))
        assertTrue(BlazeParsing.playerInPros(players, 222L))
        assertFalse(BlazeParsing.playerInPros(players, 333L))
        assertFalse(BlazeParsing.playerInPros(emptyList(), 1L))
        assertFalse(BlazeParsing.playerInPros(null, 1L))
    }

    /** ULST 49 的第一个元素就是连接组数组 [30722, 2, CGID]（CardTool 同款取法）。 */
    @Test
    fun parseUserExtendedDataTakesFirstElementOfUlst49() {
        val data = mapOf<String, Any?>(
            "ULST 43" to listOf(
                mapOf<String, Any?>("EDAT 3" to mapOf("ULST 49" to listOf(listOf(30722L, 2L, 14376255790206L))))
            )
        )
        assertEquals(listOf(30722L, 2L, 14376255790206L), BlazeParsing.parseUserExtendedData(data))
    }

    @Test
    fun parseUserExtendedDataHandlesNullAndEmpty() {
        assertNull(BlazeParsing.parseUserExtendedData(null))
        assertNull(BlazeParsing.parseUserExtendedData(mapOf("ULST 43" to emptyList<Any?>())))
        assertNull(BlazeParsing.parseUserExtendedData(mapOf("ULST 43" to listOf(emptyMap<String, Any?>()))))
    }

    @Test
    fun protocolVersionExtractedFromGameInfo() {
        val gameInfo = mapOf<String, Any?>("VSTR 1" to "3779779")
        assertEquals("3779779", BlazeParsing.protocolVersionFrom(gameInfo))
        assertEquals("", BlazeParsing.protocolVersionFrom(null))
    }
}
