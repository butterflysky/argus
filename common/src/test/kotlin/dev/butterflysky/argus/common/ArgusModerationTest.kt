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
        val cfg = ArgusSettings(cacheFile = cache.toString())
        val cfgPath = tempDir.resolve("argus.json")
        Files.writeString(cfgPath, Json.encodeToString(cfg))
        ArgusConfig.load(cfgPath)
        CacheStore.load(cache)
        return cache
    }

    @Test
    fun `ban denies login with reason`() {
        loadConfig()
        val uuid = UUID.randomUUID()
        ArgusCore.banPlayer(uuid, actor = 123L, reason = "Griefing", untilEpochMillis = System.currentTimeMillis() + 60_000)

        val result = ArgusCore.onPlayerLogin(uuid, "player", isOp = false, isLegacyWhitelisted = false)
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
}
