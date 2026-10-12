package com.bf1.admin.tool.ui.home

import com.bf1.admin.tool.data.local.entity.AccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountFallbackTest {

    @Test
    fun `test fallback picks first account when no account is active`() {
        val accounts = listOf(
            AccountEntity(id = 1, name = "Account1", personaId = "1001", remid = "r1", sid = "s1", isActive = false),
            AccountEntity(id = 2, name = "Account2", personaId = "1002", remid = "r2", sid = "s2", isActive = false)
        )
        val active = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
        assertEquals(1L, active?.id)
        assertEquals("Account1", active?.name)
    }

    @Test
    fun `test fallback picks active account when one is active`() {
        val accounts = listOf(
            AccountEntity(id = 1, name = "Account1", personaId = "1001", remid = "r1", sid = "s1", isActive = false),
            AccountEntity(id = 2, name = "Account2", personaId = "1002", remid = "r2", sid = "s2", isActive = true)
        )
        val active = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
        assertEquals(2L, active?.id)
        assertEquals("Account2", active?.name)
    }

    @Test
    fun `test fallback is null when accounts list is empty`() {
        val accounts = emptyList<AccountEntity>()
        val active = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
        assertNull(active)
    }

    @Test
    fun `test deleting active account picks next remaining account`() {
        val accounts = listOf(
            AccountEntity(id = 1, name = "Account1", personaId = "1001", remid = "r1", sid = "s1", isActive = true),
            AccountEntity(id = 2, name = "Account2", personaId = "1002", remid = "r2", sid = "s2", isActive = false)
        )
        val deleted = accounts[0]
        val remaining = accounts.filter { it.id != deleted.id }
        val nextActive = remaining.firstOrNull()
        assertEquals(2L, nextActive?.id)
        assertEquals("Account2", nextActive?.name)
    }
}
