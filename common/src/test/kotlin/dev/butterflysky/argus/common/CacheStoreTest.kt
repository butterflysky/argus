package dev.butterflysky.argus.common

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheStoreTest : ArgusTestBase() {
    @Test
    fun `save creates backup and load falls back to bak`() {
        configureArgus()
        val cachePath = ArgusConfig.cachePath
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "PlayerOne", discordId = 77L, hasAccess = true))
        CacheStore.appendEvent(EventEntry(type = "first_allow", targetUuid = uuid.toString()))

        CacheStore.save(cachePath).getOrThrow()
        // Second save should roll primary to .bak
        assertTrue(Files.exists(cachePath))
        CacheStore.save(cachePath).getOrThrow()
        CacheStore.save(cachePath).getOrThrow()
        val backup = cachePath.resolveSibling("${cachePath.fileName}.bak")
        assertTrue(Files.exists(backup))

        // Corrupt primary to force .bak fallback.
        Files.writeString(cachePath, "{invalid json")

        CacheStore.load(cachePath).getOrThrow()
        assertEquals("PlayerOne", CacheStore.get(uuid)?.mcName)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "first_allow" })
    }

    @Test
    fun `enqueueSave flushes pending writes`() {
        configureArgus()
        val cachePath: Path = ArgusConfig.cachePath
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "QueuedSave"))

        CacheStore.enqueueSave(cachePath)
        assertTrue(CacheStore.flushSaves())
        assertTrue(Files.exists(cachePath))
        val contents = Files.readString(cachePath)
        assertTrue(contents.contains("QueuedSave"))
    }

    @Test
    fun `finders locate entries by discord id and name`() {
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "Lookup", discordId = 99L))

        assertEquals(uuid, CacheStore.findByDiscordId(99L)?.first)
        assertEquals(uuid, CacheStore.findByName("lookup")?.first)
    }
}
