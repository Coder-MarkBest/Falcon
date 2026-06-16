package com.falcon.ipc.security

import org.junit.Assert.*
import org.junit.Test

class SignatureTrustTest {
    @Test fun `self signature is trusted`() {
        assertTrue(SignatureGuard.isTrusted(setOf("selfhash"), setOf("selfhash")))
    }
    @Test fun `whitelisted third-party signature is trusted`() {
        assertTrue(SignatureGuard.isTrusted(setOf("thirdhash"), setOf("selfhash", "thirdhash")))
    }
    @Test fun `unknown signature is rejected`() {
        assertFalse(SignatureGuard.isTrusted(setOf("evilhash"), setOf("selfhash")))
    }
    @Test fun `empty caller signatures rejected`() {
        assertFalse(SignatureGuard.isTrusted(emptySet(), setOf("selfhash")))
    }
    @Test fun `mixed caller packages - one untrusted rejects all`() {
        assertFalse(SignatureGuard.isTrusted(setOf("selfhash", "evilhash"), setOf("selfhash")))
    }
}
