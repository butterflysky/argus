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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Local cache of player data plus append-only audit streams (events, applications).
 * This is the sole source of truth for login checks; no live Discord reads on the login thread.
 */
object CacheStore {
    private val logger = LoggerFactory.getLogger("argus-cache")
    private val json = Json { prettyPrint = true }
    private val data: MutableMap<UUID, PlayerData> = ConcurrentHashMap()
    private val events: MutableList<EventEntry> = mutableListOf()
    private val applications: MutableList<WhitelistApplication> = mutableListOf()
    private val saver = SaveScheduler()

    fun snapshot(): Map<UUID, PlayerData> = data.toMap()

    fun eventsSnapshot(): List<EventEntry> = events.toList()

    fun applicationsSnapshot(): List<WhitelistApplication> = applications.toList()

    fun get(uuid: UUID): PlayerData? = data[uuid]

    fun upsert(
        uuid: UUID,
        player: PlayerData,
    ) {
        data[uuid] = player
    }

    fun findByDiscordId(discordId: Long): Pair<UUID, PlayerData>? = data.entries.firstOrNull { it.value.discordId == discordId }?.toPair()

    fun findByUuid(uuid: UUID): PlayerData? = data[uuid]

    fun findByName(name: String): Pair<UUID, PlayerData>? =
        data.entries.firstOrNull { it.value.mcName?.equals(name, ignoreCase = true) == true }?.toPair()

    fun appendEvent(event: EventEntry) {
        events += event
    }

    fun addApplication(app: WhitelistApplication) {
        applications += app
    }

    fun updateApplication(
        id: String,
        updater: (WhitelistApplication) -> WhitelistApplication?,
    ): WhitelistApplication? {
        val idx = applications.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = updater(applications[idx]) ?: return null
        applications[idx] = updated
        return updated
    }

    fun getApplication(id: String): WhitelistApplication? = applications.firstOrNull { it.id == id }

    fun load(cachePath: Path): Result<Unit> {
        val primary = cachePath
        val backup = cachePath.resolveSibling(cachePath.fileName.toString() + ".bak")

        return runCatching {
            val loaded =
                readFile(primary).recoverCatching { ex ->
                    logger.warn("Primary cache load failed, attempting .bak fallback: ${ex.message}")
                    readFile(backup).getOrThrow()
                }.getOrElse { SerializableCache() }

            data.clear()
            events.clear()
            applications.clear()

            data.putAll(loaded.players.mapKeys { UUID.fromString(it.key) })
            events.addAll(loaded.events)
            applications.addAll(loaded.applications)
            logger.info("Loaded argus cache with ${data.size} entries, ${events.size} events, ${applications.size} applications")
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

            val serializable =
                SerializableCache(
                    players = data.mapKeys { it.key.toString() },
                    events = events,
                    applications = applications,
                )
            Files.writeString(primary, json.encodeToString(serializable))
            logger.info("Saved argus cache (${data.size} entries) to ${primary.toAbsolutePath()}")
        }.onFailure { ex ->
            logger.error("Failed to save argus cache: ${ex.message}", ex)
        }
    }

    /** Schedule a save on a background executor, coalescing bursty callers. */
    fun enqueueSave(cachePath: Path) {
        saver.request { save(cachePath) }
    }

    /** Testing/maintenance hook to flush pending saves synchronously. */
    internal fun flushSaves(timeoutMillis: Long = 2000): Boolean = saver.flush(timeoutMillis)

    private class SaveScheduler {
        private val executor =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "argus-cache-save").apply { isDaemon = true }
            }
        private val lock = Any()
        private var pending: ScheduledFuture<*>? = null

        fun request(task: () -> Result<Unit>) {
            synchronized(lock) {
                if (pending?.isDone == false) return
                pending = executor.schedule({ task.invoke() }, 200, TimeUnit.MILLISECONDS)
            }
        }

        fun flush(timeoutMillis: Long): Boolean {
            val future = synchronized(lock) { pending }
            future?.get(timeoutMillis, TimeUnit.MILLISECONDS)
            return future?.isDone ?: true
        }
    }

    private fun readFile(path: Path): Result<SerializableCache> =
        runCatching {
            if (!Files.exists(path)) return@runCatching SerializableCache()
            val text = Files.readString(path)
            json.decodeFromString(SerializableCache.serializer(), text)
        }.onFailure { ex ->
            if (ex !is IOException) {
                logger.error("Error decoding cache file $path: ${ex.message}", ex)
            }
        }

    @Serializable
    data class SerializableCache(
        val players: Map<String, PlayerData> = emptyMap(),
        val events: List<EventEntry> = emptyList(),
        val applications: List<WhitelistApplication> = emptyList(),
    )
}

@Serializable
data class EventEntry(
    val type: String,
    val targetUuid: String? = null,
    val targetDiscordId: Long? = null,
    val actorDiscordId: Long? = null,
    val message: String? = null,
    val untilEpochMillis: Long? = null,
    val atEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class WhitelistApplication(
    val id: String,
    val discordId: Long,
    val mcName: String,
    val resolvedUuid: String? = null,
    val status: String = "pending",
    val reason: String? = null,
    val submittedAtEpochMillis: Long = System.currentTimeMillis(),
    val decidedAtEpochMillis: Long? = null,
    val decidedByDiscordId: Long? = null,
)
