package dev.butterflysky.argus.common

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArgusCoreLoginTest {

    @BeforeEach
    fun resetCache(@TempDir tempDir: Path) {
        CacheStore.load(tempDir.resolve("argus_db.json"))
    }

    @Test
    fun `op bypasses gate`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "op", isOp = true, isLegacyWhitelisted = false)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player with access is allowed`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(hasAccess = true))

        val result = ArgusCore.onPlayerLogin(playerId, "linked", isOp = false, isLegacyWhitelisted = false)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player missing role is denied`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(hasAccess = false))

        val result = ArgusCore.onPlayerLogin(playerId, "no-role", isOp = false, isLegacyWhitelisted = false)
        val deny = assertIs<LoginResult.Deny>(result)
        assertEquals("Access Denied: Missing Discord Role", deny.message)
    }

    @Test
    fun `legacy whitelisted gets verification token kick`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true)
        val kick = assertIs<LoginResult.AllowWithKick>(result)
        assertTrue(kick.message.startsWith("Verification Required: !link"))
    }

    @Test
    fun `stranger is denied with application message`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "stranger", isOp = false, isLegacyWhitelisted = false)
        val deny = assertIs<LoginResult.Deny>(result)
        assertEquals(ArgusConfig.current().applicationMessage, deny.message)
    }
}
