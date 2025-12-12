package dev.butterflysky.argus.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArgusScenarioIntegrationTest {
    private val auditLogs = mutableListOf<String>()

    @BeforeEach
    fun setup(
        @TempDir tempDir: Path,
    ) {
        auditLogs.clear()
        AuditLogger.configure { auditLogs += it.toConsoleString() }
        ArgusCore.setDiscordStartedOverride(true)
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
    }

    @Test
    fun `whitelist off allows everyone`() {
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "player",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = false,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `misconfigured argus falls back to vanilla allow`() {
        // Simulate misconfig
        ArgusCore.setDiscordStartedOverride(false)
        val result =
            ArgusCore.onPlayerLogin(
                UUID.randomUUID(),
                "player",
                isOp = false,
                isLegacyWhitelisted = false,
                whitelistEnabled = true,
            )
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `misconfigured argus allows legacy whitelisted`() {
        ArgusCore.setDiscordStartedOverride(false)
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `stranger allowed and handled by vanilla whitelist`() {
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
    fun `linked whitelisted allows and logs first allow`() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 10L, hasAccess = true, mcName = "mc", discordName = "dname"))

        val result = ArgusCore.onPlayerLogin(uuid, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertTrue(auditLogs.any { it.contains("First login") })
    }

    @Test
    fun `legacy unlinked is denied with link token`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("/link"))
    }

    @Test
    fun `op bypasses all checks`() {
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "op", isOp = true, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `active timed ban denies with reason`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(
            playerId,
            PlayerData(
                hasAccess = true,
                banReason = "Griefing",
                banUntilEpochMillis = System.currentTimeMillis() + 60_000,
            ),
        )
        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("Griefing"))
    }

    @Test
    fun `expired ban allows`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(
            playerId,
            PlayerData(
                hasAccess = true,
                banReason = "Temp ban",
                banUntilEpochMillis = System.currentTimeMillis() - 1_000,
            ),
        )
        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun `permanent ban denies`() {
        val playerId = UUID.randomUUID()
        CacheStore.upsert(
            playerId,
            PlayerData(
                hasAccess = true,
                banReason = "Permanent ban",
                banUntilEpochMillis = Long.MAX_VALUE,
            ),
        )
        val result = ArgusCore.onPlayerLogin(playerId, "mc", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("Permanent ban", ignoreCase = true) || deny.message.contains("permanent", ignoreCase = true))
    }
}
