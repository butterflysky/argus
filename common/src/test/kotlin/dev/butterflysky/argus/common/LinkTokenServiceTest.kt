package dev.butterflysky.argus.common

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkTokenServiceTest : ArgusTestBase() {
    @Test
    fun `issuing token for same uuid reuses existing token`() {
        val uuid = UUID.randomUUID()
        val first = LinkTokenService.issueToken(uuid, "Alpha")
        val second = LinkTokenService.issueToken(uuid, "Alpha")
        assertEquals(first, second)
        val active = LinkTokenService.listActive().first { it.uuid == uuid }
        assertEquals("Alpha", active.mcName)
    }

    @Test
    fun `consume removes token from active list`() {
        val uuid = UUID.randomUUID()
        val token = LinkTokenService.issueToken(uuid, "Beta")
        assertTrue(LinkTokenService.listActive().any { it.token == token })

        val consumed = LinkTokenService.consume(token)
        assertEquals(uuid, consumed?.uuid)
        assertTrue(LinkTokenService.listActive().none { it.token == token })
    }

    @Test
    fun `reissuing token upgrades stored minecraft name`() {
        val uuid = UUID.randomUUID()
        val token = LinkTokenService.issueToken(uuid, "player")
        assertEquals("player", LinkTokenService.listActive().first { it.token == token }.mcName)

        val sameToken = LinkTokenService.issueToken(uuid, "BetterName")
        assertEquals(token, sameToken)

        val active = LinkTokenService.listActive().first { it.token == token }
        assertEquals("BetterName", active.mcName)
    }
}
