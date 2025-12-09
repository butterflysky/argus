package dev.butterflysky.argus.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArgusLoginScriptedTest {
    private val harness = LoginHarness()

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        val cache = dir.resolve("argus_db.json")
        val cfgPath = dir.resolve("argus.json")
        val cfg =
            ArgusSettings(
                botToken = "token",
                guildId = 1,
                whitelistRoleId = 2,
                adminRoleId = 3,
                enforcementEnabled = true,
                cacheFile = cache.toString(),
            )
        Files.writeString(cfgPath, kotlinx.serialization.json.Json.encodeToString(ArgusSettings.serializer(), cfg))
        ArgusConfig.load(cfgPath)
        CacheStore.load(cache)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride(null)
    }

    @AfterEach
    fun tearDown() {
        ArgusCore.setDiscordStartedOverride(null)
        ArgusCore.setRoleCheckOverride(null)
    }

    @Test
    fun `unlinked but vanilla-whitelisted is kicked with link token`() {
        val uuid = UUID.randomUUID()
        val out = harness.run(uuid, "Legacy", vanillaWhitelisted = true)

        val deny = assertIs<LoginResult.Deny>(out.login)
        assertTrue(deny.message.contains("/link"))
        // join path already delivered courtesy/kick message
        assertNotNull(out.joinMessage)
    }

    @Test
    fun `missing role keeps cache false and join kicks`() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 11L, hasAccess = true, mcName = "mc"))
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val out = harness.run(uuid, "mc", vanillaWhitelisted = false)

        assertIs<LoginResult.Allow>(out.login)
        assertNotNull(out.joinMessage)
        assertTrue(out.joinMessage!!.contains("Access revoked"))
        assertTrue(CacheStore.get(uuid)?.hasAccess == false)
    }

    @Test
    fun `banned player always denied even if vanilla would allow`() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(hasAccess = true, banReason = "Grief", banUntilEpochMillis = Long.MAX_VALUE))

        val out = harness.run(uuid, "mc", vanillaWhitelisted = true)

        assertIs<LoginResult.Deny>(out.login)
    }
}
