package dev.butterflysky.argus.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArgusModerationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun loadConfig(): Path {
        val cache = tempDir.resolve("argus_db.json")
        val cfg = ArgusSettings(
            botToken = "token",
            guildId = 1L,
            whitelistRoleId = 2L,
            adminRoleId = 3L,
            cacheFile = cache.toString()
        )
        val cfgPath = tempDir.resolve("argus.json")
        Files.writeString(cfgPath, Json.encodeToString(cfg))
        ArgusConfig.load(cfgPath)
        CacheStore.load(cache)
        ArgusCore.setDiscordStartedOverride(true)
        return cache
    }

    @Test
    fun `ban denies login with reason`() {
        loadConfig()
        val uuid = UUID.randomUUID()
        ArgusCore.banPlayer(uuid, actor = 123L, reason = "Griefing", untilEpochMillis = System.currentTimeMillis() + 60_000)

        val result = ArgusCore.onPlayerLogin(uuid, "player", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertTrue(deny.message.contains("Griefing"))
    }

    @Test
    fun `approve application grants access and links`() {
        loadConfig()
        val uuid = UUID.randomUUID()
        val app = WhitelistApplication(
            id = "app-1",
            discordId = 999L,
            mcName = "Tester",
            resolvedUuid = uuid.toString(),
            status = "pending"
        )
        CacheStore.addApplication(app)

        val result = ArgusCore.approveApplication(app.id, actorDiscordId = 777L, reason = "ok")
        assertTrue(result.isSuccess)

        val pd = CacheStore.get(uuid)
        assertEquals(true, pd?.hasAccess)
        assertEquals(999L, pd?.discordId)
        val decided = CacheStore.getApplication(app.id)
        assertEquals("approved", decided?.status)
    }

    @Test
    fun `warn records event and increments count`() {
        loadConfig()
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "WarnMe"))

        ArgusCore.warnPlayer(uuid, actor = 1L, reason = "Rule 1")

        val pd = CacheStore.get(uuid)
        assertEquals(1, pd?.warnCount)
        val events = CacheStore.eventsSnapshot().filter { it.type == "warn" && it.targetUuid == uuid.toString() }
        assertEquals(1, events.size)
    }

    @Test
    fun `deny application records decision`() {
        loadConfig()
        val uuid = UUID.randomUUID()
        val app = WhitelistApplication(
            id = "app-2",
            discordId = 111L,
            mcName = "Nope",
            resolvedUuid = uuid.toString(),
            status = "pending"
        )
        CacheStore.addApplication(app)
        val result = ArgusCore.denyApplication(app.id, actorDiscordId = 222L, reason = "bad")
        assertTrue(result.isSuccess)
        val decided = CacheStore.getApplication(app.id)
        assertEquals("denied", decided?.status)
        assertEquals("bad", decided?.reason)
    }

    @Test
    fun `unconfigured Argus fails open`() {
        // Leave config with default blank botToken/guildId
        val cache = tempDir.resolve("argus_db.json")
        val cfg = ArgusSettings(cacheFile = cache.toString(), botToken = "", guildId = null, whitelistRoleId = null, adminRoleId = null)
        val cfgPath = tempDir.resolve("argus.json")
        Files.writeString(cfgPath, Json.encodeToString(cfg))
        ArgusConfig.load(cfgPath)
        CacheStore.load(cache)

        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "noop", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)
        val deny = assertIs<LoginResult.Deny>(result)
        assertEquals("[argus] ${ArgusConfig.current().applicationMessage}", deny.message)
    }
}
