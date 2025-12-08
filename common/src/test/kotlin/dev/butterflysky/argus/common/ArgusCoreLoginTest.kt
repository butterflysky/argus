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
import kotlin.test.assertTrue

class ArgusCoreLoginTest {
    private val auditLogs = mutableListOf<String>()

    @BeforeEach
    fun resetCache(
        @TempDir tempDir: Path,
    ) {
        auditLogs.clear()
        AuditLogger.configure { auditLogs += it }
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
        ArgusCore.setDiscordStartedOverride(true)
    }

    @AfterEach
    fun tearDown() {
        AuditLogger.configure(null)
    }

    @Test
    fun `op bypasses gate`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "op", isOp = true, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player with access is allowed`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(hasAccess = true))

        val result = ArgusCore.onPlayerLogin(playerId, "linked", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player missing role is denied`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(hasAccess = false))

        val result = ArgusCore.onPlayerLogin(playerId, "no-role", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertEquals("[argus] ${ArgusConfig.current().applicationMessage}", deny.message)
    }

    @Test
    fun `dry-run allows but logs missing role`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 42L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertTrue(auditLogs.any { it.contains("[DRY-RUN] Would deny login") })
        assertEquals(true, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `legacy whitelisted gets verification token kick`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("/link"))
    }

    @Test
    fun `stranger is denied with application message`() {
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "stranger",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        val deny = assertIs<LoginResult.Deny>(result)
        assertEquals("[argus] ${ArgusConfig.current().applicationMessage}", deny.message)
    }
}
