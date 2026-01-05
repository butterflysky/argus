package dev.butterflysky.argus.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArgusCoreIntegrationTest {
    private val auditLogs = mutableListOf<String>()

    @BeforeEach
    fun setup(
        @TempDir tempDir: Path,
    ) {
        auditLogs.clear()
        AuditLogger.configure { auditLogs += it.toConsoleString() }
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setDiscordStartOverride(null)
        ArgusCore.setDiscordStopOverride(null)
        ArgusCore.setRoleCheckOverride(null)

        val cachePath = tempDir.resolve("argus_db.json")
        val cfgPath = tempDir.resolve("argus.json")
        val cfg =
            ArgusSettings(
                botToken = "token",
                guildId = 1L,
                whitelistRoleId = 2L,
                adminRoleId = 3L,
                enforcementEnabled = true,
                cacheFile = cachePath.toString(),
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
        ArgusCore.setDiscordStartOverride(null)
        ArgusCore.setDiscordStopOverride(null)
    }

    @Test
    fun `cache deny refreshed to allow`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = false, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.HasRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        val updated = CacheStore.get(playerId)
        assertTrue(updated?.hasAccess == true)
    }

    @Test
    fun `cache deny refreshed to deny`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = false, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        val updated = CacheStore.get(playerId)
        assertEquals(false, updated?.hasAccess)
    }

    @Test
    fun `join refresh kicks when role removed`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val kickMsg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertNotNull(kickMsg)
        assertTrue(kickMsg.contains("Access revoked"))
        val updated = CacheStore.get(playerId)
        assertEquals(false, updated?.hasAccess)
    }

    @Test
    fun `join refresh kicks when user left guild`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val kickMsg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertNotNull(kickMsg)
        assertTrue(kickMsg.contains("left Discord guild"))
        val updated = CacheStore.get(playerId)
        assertEquals(false, updated?.hasAccess)
        assertTrue(auditLogs.any { it.contains("left Discord guild", ignoreCase = true) })
    }

    @Test
    fun `transient discord failure leaves cache unchanged`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.Indeterminate }

        val msg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertNotNull(msg) // welcome message still delivered
        val updated = CacheStore.get(playerId)
        assertEquals(true, updated?.hasAccess)
    }

    @Test
    fun `join refresh dry-run missing role does not kick`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val msg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertEquals(null, msg)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
        assertTrue(auditLogs.any { it.contains("Access review (dry-run)") && it.contains("Would revoke") })
    }

    @Test
    fun `join refresh dry-run not in guild does not kick`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val msg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true)

        assertEquals(null, msg)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
        assertTrue(auditLogs.any { it.contains("Access revoked") && it.contains("Left Discord guild") })
    }

    @Test
    fun `minecraft name change is logged and cached`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(mcName = "old"))

        ArgusCore.onPlayerLogin(playerId, "newname", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertTrue(auditLogs.any { it.contains("MC name changed") && it.contains("old -> newname") })
        assertEquals("newname", CacheStore.get(playerId)?.mcName)
    }

    @Test
    fun `discord identity change is logged and cached`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 50L, discordName = "oldName", discordNick = "oldNick"))

        DiscordBridge.applyIdentityChange(50L, oldName = "oldName", newName = "newName", oldNick = "oldNick", newNick = "newNick")

        assertEquals("newName", CacheStore.get(playerId)?.discordName)
        assertEquals("newNick", CacheStore.get(playerId)?.discordNick)
        assertTrue(auditLogs.any { it.contains("Discord name changed") && it.contains("oldName -> newName") })
        assertTrue(auditLogs.any { it.contains("Discord nick changed") && it.contains("oldNick") && it.contains("newNick") })
    }

    @Test
    fun `link audit uses provided minecraft name`() {
        val playerId = UUID.randomUUID()
        val mcName = "DanNGan"

        // Player joins unlinked; token should carry MC name
        val joinMsg = ArgusCore.onPlayerJoin(playerId, isOp = false, whitelistEnabled = true, mcName = mcName)
        assertNotNull(joinMsg)

        val token = LinkTokenService.listActive().first { it.uuid == playerId }.token
        ArgusCore.linkDiscordUser(token, 555555555555555555L, "TestDiscordUser", "ServerNick")

        val cached = CacheStore.get(playerId)
        assertEquals(mcName, cached?.mcName)
        assertEquals("ServerNick", cached?.discordNick)
        assertEquals("TestDiscordUser", cached?.discordName)
        assertTrue(
            auditLogs.any {
                it.contains("Link complete") &&
                    it.contains(mcName) &&
                    (it.contains("ServerNick") || it.contains("TestDiscordUser"))
            },
        )
    }

    @Test
    fun `reload config async surfaces discord start failure`() {
        ArgusCore.setDiscordStopOverride { }
        ArgusCore.setDiscordStartOverride { Result.failure(IllegalStateException("boom")) }

        val result = ArgusCore.reloadConfigAsync().get(5, TimeUnit.SECONDS)

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `start discord returns failure when bridge fails`() {
        ArgusCore.setDiscordStartOverride { Result.failure(IllegalStateException("nope")) }

        val result = ArgusCore.startDiscord().get(5, TimeUnit.SECONDS)

        assertTrue(result.isFailure)
        assertEquals("nope", result.exceptionOrNull()?.message)
    }

    @Test
    fun `reload with discord disabled does not lock start`() {
        ArgusCore.setDiscordStopOverride { }
        ArgusConfig.update("botToken", "")
        ArgusConfig.update("guildId", "")

        val disabled = ArgusCore.reloadConfigAsync().get(5, TimeUnit.SECONDS)

        assertTrue(disabled.isSuccess)

        ArgusConfig.update("botToken", "token")
        ArgusConfig.update("guildId", "123")
        ArgusCore.setDiscordStartOverride { Result.failure(IllegalStateException("late")) }

        val result = ArgusCore.startDiscord().get(5, TimeUnit.SECONDS)

        assertTrue(result.isFailure)
        assertEquals("late", result.exceptionOrNull()?.message)
    }
}
