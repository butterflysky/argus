package dev.butterflysky.argus.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local cache of player data. This is the sole source of truth for login checks.
 */
object CacheStore {
    private val logger = LoggerFactory.getLogger("argus-cache")
    private val json = Json { prettyPrint = true }
    private val data: MutableMap<UUID, PlayerData> = ConcurrentHashMap()

    fun snapshot(): Map<UUID, PlayerData> = data.toMap()

    fun get(uuid: UUID): PlayerData? = data[uuid]

    fun upsert(uuid: UUID, player: PlayerData) {
        data[uuid] = player
    }

    fun findByDiscordId(discordId: Long): Pair<UUID, PlayerData>? =
        data.entries.firstOrNull { it.value.discordId == discordId }?.toPair()

    fun findByUuid(uuid: UUID): PlayerData? = data[uuid]

    fun load(cachePath: Path): Result<Unit> {
        val primary = cachePath
        val backup = cachePath.resolveSibling(cachePath.fileName.toString() + ".bak")

        return runCatching {
            val loaded = readFile(primary).recoverCatching { ex ->
                logger.warn("Primary cache load failed, attempting .bak fallback: ${ex.message}")
                readFile(backup).getOrThrow()
            }.getOrElse { emptyMap() }

            data.clear()
            data.putAll(loaded)
            logger.info("Loaded argus cache with ${data.size} entries")
        }
    }

    fun save(cachePath: Path): Result<Unit> {
        val primary = cachePath
        val backup = cachePath.resolveSibling(cachePath.fileName.toString() + ".bak")
        return runCatching {
            Files.createDirectories(primary.parent)

            if (Files.exists(primary)) {
                Files.move(primary, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }

            val serializable = SerializableCache(data.mapKeys { it.key.toString() })
            Files.writeString(primary, json.encodeToString(serializable))
            logger.info("Saved argus cache (${data.size} entries) to ${primary.toAbsolutePath()}")
        }.onFailure { ex ->
            logger.error("Failed to save argus cache: ${ex.message}", ex)
        }
    }

    private fun readFile(path: Path): Result<Map<UUID, PlayerData>> = runCatching {
        if (!Files.exists(path)) return@runCatching emptyMap()
        val text = Files.readString(path)
        val serializable = json.decodeFromString(SerializableCache.serializer(), text)
        serializable.players.mapKeys { UUID.fromString(it.key) }
    }.onFailure { ex ->
        if (ex !is IOException) {
            logger.error("Error decoding cache file ${path}: ${ex.message}", ex)
        }
    }

    @Serializable
    private data class SerializableCache(
        val players: Map<String, PlayerData>
    )
}
