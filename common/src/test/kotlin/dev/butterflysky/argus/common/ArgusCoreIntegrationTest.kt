package dev.butterflysky.argus.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArgusCoreIntegrationTest {

    private val auditLogs = mutableListOf<String>()

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        auditLogs.clear()
        AuditLogger.configure { auditLogs += it }
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride(null)

        val cachePath = tempDir.resolve("argus_db.json")
        val cfgPath = tempDir.resolve("argus.json")
        val cfg = ArgusSettings(
            botToken = "token",
            guildId = 1L,
            whitelistRoleId = 2L,
            adminRoleId = 3L,
            cacheFile = cachePath.toString()
        )
        Files.writeString(cfgPath, kotlinx.serialization.json.Json.encodeToString(ArgusSettings.serializer(), cfg))
        ArgusConfig.load(cfgPath)
        CacheStore.load(cachePath)
    }

    @AfterEach
    fun tearDown() {
        AuditLogger.configure(null)
        ArgusCore.setRoleCheckOverride(null)
        ArgusCore.setDiscordStartedOverride(null)
    }

    @Test
    fun `cache deny refreshed to allow`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = false, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { true }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        val updated = CacheStore.get(playerId)
        assertTrue(updated?.hasAccess == true)
    }

    @Test
    fun `cache deny refreshed to deny`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = false, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { false }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains(ArgusConfig.current().applicationMessage))
        val updated = CacheStore.get(playerId)
        assertEquals(false, updated?.hasAccess)
    }

    @Test
    fun `join refresh kicks when role removed`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { false }

        val kickMsg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertNotNull(kickMsg)
        assertTrue(kickMsg.contains("Access revoked"))
        val updated = CacheStore.get(playerId)
        assertEquals(false, updated?.hasAccess)
    }

    @Test
    fun `minecraft name change is logged and cached`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(mcName = "old"))

        ArgusCore.onPlayerLogin(playerId, "newname", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertTrue(auditLogs.any { it.contains("MC name changed: old -> newname") })
        assertEquals("newname", CacheStore.get(playerId)?.mcName)
    }

    @Test
    fun `discord identity change is logged and cached`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 50L, discordName = "oldName", discordNick = "oldNick"))

        DiscordBridge.applyIdentityChange(50L, oldName = "oldName", newName = "newName", oldNick = "oldNick", newNick = "newNick")

        assertEquals("newName", CacheStore.get(playerId)?.discordName)
        assertEquals("newNick", CacheStore.get(playerId)?.discordNick)
        assertTrue(auditLogs.any { it.contains("Discord name changed: oldName -> newName") })
        assertTrue(auditLogs.any { it.contains("Discord nick changed: oldNick") })
    }
}
