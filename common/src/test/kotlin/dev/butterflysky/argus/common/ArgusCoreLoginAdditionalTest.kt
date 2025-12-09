package dev.butterflysky.argus.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Path
import java.util.UUID

class ArgusCoreLoginAdditionalTest {
    private lateinit var cachePath: Path
    private lateinit var configPath: Path

    @BeforeEach
    fun setup() {
        val tmp = createTempDirectory("argus-added-")
        cachePath = tmp.resolve("argus_db.json")
        configPath = tmp.resolve("argus.json")

        val settings =
            ArgusSettings(
                botToken = "token",
                guildId = 1,
                whitelistRoleId = 2,
                adminRoleId = 3,
                logChannelId = 4,
                cacheFile = cachePath.toString(),
                applicationMessage = "Access Denied: Please apply in Discord.",
                enforcementEnabled = true,
            )
        val json = Json { prettyPrint = true }
        configPath.writeText(json.encodeToString(ArgusSettings.serializer(), settings))

        ArgusConfig.load(configPath)
        CacheStore.load(cachePath)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride(null)
    }

    @AfterEach
    fun tearDown() {
        ArgusCore.setDiscordStartedOverride(null)
        ArgusCore.setRoleCheckOverride(null)
        CacheStore.flushSaves()
    }

    @Test
    fun allow_when_cached_access_true() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(hasAccess = true, mcName = "Alice"))

        val result = ArgusCore.onPlayerLogin(uuid, "Alice", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
    }

    @Test
    fun allow_when_not_in_guild_but_cache_updates() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 123L, hasAccess = false, mcName = "Bob"))
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val result = ArgusCore.onPlayerLogin(uuid, "Bob", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertIs<LoginResult.Allow>(result)
        assertTrue(CacheStore.get(uuid)?.hasAccess == false)
    }

    @Test
    fun legacy_whitelisted_receives_link_token() {
        val uuid = UUID.randomUUID()

        val result = ArgusCore.onPlayerLogin(uuid, "Legacy", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.revokeWhitelist)
        assertTrue(deny.message.contains("/link"))
    }
}
