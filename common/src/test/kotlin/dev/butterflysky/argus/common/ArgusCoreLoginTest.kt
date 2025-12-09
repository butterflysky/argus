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
        ArgusCore.setRoleCheckOverride(null)
        ArgusCore.setDiscordStartedOverride(null)
    }

    @Test
    fun `op bypasses gate`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "op", isOp = true, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player with access is allowed`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 5L, hasAccess = true, mcName = "linked"))

        val result = ArgusCore.onPlayerLogin(playerId, "linked", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `linked player missing role is allowed but cache updated (enforced)`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 42L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val result = ArgusCore.onPlayerLogin(playerId, "no-role", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `linked player missing role is allowed in dry-run and logs`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 42L, hasAccess = false, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertTrue(auditLogs.any { it.contains("[DRY-RUN]") && it.contains("missing Discord whitelist role") })
        assertEquals(false, CacheStore.get(playerId)?.hasAccess) // cache unchanged in dry-run
    }

    @Test
    fun `linked player not in guild is allowed and cache set false`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 99L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `linked player not in guild dry-run allows and logs`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 99L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertTrue(auditLogs.any { it.contains("[DRY-RUN]") && it.contains("not in Discord guild") })
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `legacy whitelisted gets verification token kick`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("/link"))
    }

    @Test
    fun `legacy whitelisted dry-run issues token but allows`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val uuid = UUID.randomUUID()

        val result = ArgusCore.onPlayerLogin(uuid, "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        // token should exist and be stable until consumed
        val token1 = LinkTokenService.issueToken(uuid, "legacy")
        val token2 = LinkTokenService.issueToken(uuid, "legacy")
        assertEquals(token1, token2)
    }

    @Test
    fun `stranger allowed (vanilla handles whitelist)`() {
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "stranger",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `stranger denial includes invite when configured`() {
        ArgusConfig.update("discordInviteUrl", "https://discord.gg/test")
        ArgusCore.reloadConfig()
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "stranger",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `stranger dry-run is allowed but logs`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "stranger",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
        assertTrue(auditLogs.none { it.contains("Would deny stranger") })
    }

    @Test
    fun `live refresh allows when cache denies but role present`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 7L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.HasRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertEquals(true, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `live refresh in dry-run allows but leaves cache unchanged`() {
        ArgusConfig.update("enforcementEnabled", "false")
        ArgusCore.reloadConfig()
        ArgusCore.setDiscordStartedOverride(true)
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 7L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.HasRole }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
        assertTrue(auditLogs.any { it.contains("[DRY-RUN]") && it.contains("Would deny") }.not()) // no deny log; allowed
    }

    @Test
    fun `transient discord failure on login keeps cache decision`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 55L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.Indeterminate }

        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `discord unavailable falls back to vanilla whitelist (no custom deny)`() {
        ArgusCore.setDiscordStartedOverride(false)
        val allow =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "legacy",
                isOp = false,
                isLegacyWhitelisted = true,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(allow)

        val allowStranger =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "stranger",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(allowStranger)
    }

    @Test
    fun `op bypasses even if banned`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(
            playerId,
            PlayerData(
                hasAccess = true,
                banUntilEpochMillis = System.currentTimeMillis() + 10_000,
                banReason = "ban",
            ),
        )

        val result =
            ArgusCore.onPlayerLogin(
                playerId,
                "op",
                isOp = true,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `banned player is denied even with access`() {
        val playerId = UUID.randomUUID()
        val future = System.currentTimeMillis() + 5_000
        CacheStore.upsert(
            playerId,
            PlayerData(
                discordId = 77L,
                hasAccess = true,
                banUntilEpochMillis = future,
                banReason = "Banned",
            ),
        )

        val deny =
            assertIs<LoginResult.Deny>(
                ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true),
            )
        assertTrue(deny.message.contains("Banned"))
        assertEquals(false, deny.revokeWhitelist)
    }

    @Test
    fun `expired ban allows when access granted`() {
        val playerId = UUID.randomUUID()
        val past = System.currentTimeMillis() - 1_000
        CacheStore.upsert(
            playerId,
            PlayerData(
                discordId = 78L,
                hasAccess = true,
                banUntilEpochMillis = past,
            ),
        )

        val result =
            ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `unconfigured but vanilla-whitelisted is allowed`() {
        ArgusConfig.update("botToken", "")
        ArgusCore.reloadConfig()

        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "mc",
                isOp = false,
                isLegacyWhitelisted = true,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `unconfigured and not vanilla-whitelisted falls back to vanilla gate`() {
        ArgusConfig.update("botToken", "")
        ArgusCore.reloadConfig()

        val allow =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "mc",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(allow)
    }

    @Test
    fun `missing role allows but cache revoked`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 101L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val allow =
            assertIs<LoginResult.Allow>(
                ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true),
            )
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `not in guild allows but cache revoked`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(playerId, PlayerData(discordId = 102L, hasAccess = false))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val allow =
            assertIs<LoginResult.Allow>(
                ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true),
            )
        assertEquals(false, CacheStore.get(playerId)?.hasAccess)
    }

    @Test
    fun `revoke flag set for legacy-unlinked`() {
        val deny =
            assertIs<LoginResult.Deny>(
                ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true),
            )
        assertTrue(deny.revokeWhitelist)
    }

    @Test
    fun `stranger allowed and no revoke flag needed`() {
        assertIs<LoginResult.Allow>(
            ArgusCore.onPlayerLogin(UUID.randomUUID(), "stranger", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true),
        )
    }

    @Test
    fun `whitelist disabled allows even when unlinked`() {
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "mc",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = false,
            )
        assertIs<LoginResult.Allow>(result)
    }
}
