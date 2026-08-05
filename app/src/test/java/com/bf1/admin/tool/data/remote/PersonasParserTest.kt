package com.bf1.admin.tool.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonasParserTest {

    @Test
    fun parsePersonaIdReturnsFirstPersonaId() {
        val json = JSONObject(
            """{"personas":{"persona":[{"personaId":"123456789","displayName":"PlayerOne"}]}}"""
        )
        assertEquals("123456789", parsePersonaId(json))
    }

    @Test
    fun parsePersonaIdThrowsWhenPersonasMissing() {
        val json = JSONObject("""{}""")
        assertThrows(PersonaNotFoundException::class.java) { parsePersonaId(json) }
    }

    @Test
    fun parsePersonaIdThrowsWhenPersonaArrayEmpty() {
        val json = JSONObject("""{"personas":{"persona":[]}}""")
        assertThrows(PersonaNotFoundException::class.java) { parsePersonaId(json) }
    }

    @Test
    fun parsePersonaIdThrowsWhenPersonaIdFieldMissing() {
        val json = JSONObject("""{"personas":{"persona":[{"displayName":"NoPid"}]}}""")
        assertThrows(PersonaNotFoundException::class.java) { parsePersonaId(json) }
    }
}
