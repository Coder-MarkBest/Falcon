package com.falcon.ipc.security

import org.junit.Assert.*
import org.junit.Test

class PermissionCheckerTest {

    @Test
    fun `no rules means allow all`() {
        val checker = PermissionChecker(emptyMap())
        assertTrue(checker.check("any.service", ":anyProcess"))
    }

    @Test
    fun `allowList permits listed process`() {
        val rules = mapOf(
            "com.INavService" to AccessRule(
                allowList = setOf(":cluster", ":hud"),
                denyList = emptySet()
            )
        )
        val checker = PermissionChecker(rules)
        assertTrue(checker.check("com.INavService", ":cluster"))
        assertTrue(checker.check("com.INavService", ":hud"))
    }

    @Test
    fun `allowList rejects unlisted process`() {
        val rules = mapOf(
            "com.INavService" to AccessRule(
                allowList = setOf(":cluster"),
                denyList = emptySet()
            )
        )
        val checker = PermissionChecker(rules)
        assertFalse(checker.check("com.INavService", ":media"))
    }

    @Test
    fun `denyList blocks listed process`() {
        val rules = mapOf(
            "com.INavService" to AccessRule(
                allowList = emptySet(),
                denyList = setOf(":diagnostic")
            )
        )
        val checker = PermissionChecker(rules)
        assertFalse(checker.check("com.INavService", ":diagnostic"))
        assertTrue(checker.check("com.INavService", ":cluster"))
    }

    @Test
    fun `denyList takes precedence over allowList`() {
        val rules = mapOf(
            "com.INavService" to AccessRule(
                allowList = setOf(":cluster", ":diagnostic"),
                denyList = setOf(":diagnostic")
            )
        )
        val checker = PermissionChecker(rules)
        assertTrue(checker.check("com.INavService", ":cluster"))
        assertFalse(checker.check("com.INavService", ":diagnostic"))
    }
}
