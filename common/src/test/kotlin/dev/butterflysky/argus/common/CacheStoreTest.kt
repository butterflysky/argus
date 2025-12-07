package dev.butterflysky.argus.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

class CacheStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load falls back to bak when primary is corrupt`() {
        val cachePath = tempDir.resolve("argus_db.json")
        CacheStore.load(cachePath)

        val playerId = UUID.randomUUID()
        val first = PlayerData(discordId = 123L, hasAccess = true, mcName = "alice")
        CacheStore.upsert(playerId, first)
        CacheStore.save(cachePath).getOrThrow()

        val second = first.copy(discordId = 456L, mcName = "alice2")
        CacheStore.upsert(playerId, second)
        CacheStore.save(cachePath).getOrThrow()

        // Corrupt the primary file; .bak should still contain the first version
        Files.writeString(cachePath, "{corrupt")

        CacheStore.load(cachePath).getOrThrow()

        val snapshot = CacheStore.snapshot()
        assertEquals(first, snapshot[playerId])
    }
}
