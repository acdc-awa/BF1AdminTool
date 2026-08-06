package com.bf1.admin.tool.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonUtilsTest {

    @Test
    fun jsonOptStringReturnsNullForExplicitJsonNull() {
        val json = JSONObject("""{"personaId":null,"name":"PlayerOne"}""")
        assertNull(jsonOptString(json, "personaId"))
        assertEquals("PlayerOne", jsonOptString(json, "name"))
    }

    @Test
    fun jsonOptStringReturnsNullForMissingKey() {
        assertNull(jsonOptString(JSONObject("""{}"""), "personaId"))
    }

    @Test
    fun jsonOptStringReturnsNullForEmptyString() {
        assertNull(jsonOptString(JSONObject("""{"id":""}"""), "id"))
    }

    @Test
    fun jsonOptStringReturnsValueForNonEmptyString() {
        assertEquals("123456789", jsonOptString(JSONObject("""{"id":"123456789"}"""), "id"))
    }

    @Test
    fun jsonOptStringCoercesNumberToString() {
        assertEquals("123456789", jsonOptString(JSONObject("""{"id":123456789}"""), "id"))
    }

    @Test
    fun requirePersonaIdThrowsForExplicitNull() {
        val json = JSONObject("""{"personaId":null}""")
        assertThrows(PersonaNotFoundException::class.java) { requirePersonaId(json, "personaId") }
    }

    @Test
    fun requirePersonaIdReturnsValidValue() {
        val json = JSONObject("""{"personaId":"123"}""")
        assertEquals("123", requirePersonaId(json, "personaId"))
    }
}
