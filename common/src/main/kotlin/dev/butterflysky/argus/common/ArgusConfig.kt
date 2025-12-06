package dev.butterflysky.argus.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class ArgusSettings(
    val botToken: String = "",
    val guildId: Long? = null,
    val whitelistRoleId: Long? = null,
    val adminRoleId: Long? = null,
    val logChannelId: Long? = null,
    val applicationMessage: String = "Access Denied: Please apply in Discord.",
    val cacheFile: String = "config/argus_db.json"
)

object ArgusConfig {
    private val logger = LoggerFactory.getLogger("argus-config")
    private val json = Json { prettyPrint = true }
    private val defaultPath: Path = Paths.get("config/argus.json")
    @Volatile private var loadedPath: Path = defaultPath

    @Volatile
    private var settings: ArgusSettings = ArgusSettings()

    val cachePath: Path
        get() = Paths.get(settings.cacheFile)

    fun isConfigured(): Boolean =
        settings.botToken.isNotBlank() &&
            settings.guildId != null &&
            settings.whitelistRoleId != null &&
            settings.adminRoleId != null

    fun current(): ArgusSettings = settings

    fun load(path: Path = defaultPath): Result<ArgusSettings> = runCatching {
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) {
            Files.writeString(path, json.encodeToString(ArgusSettings.serializer(), settings))
            logger.info("Wrote default Argus config to ${path.toAbsolutePath()}")
        }

        val loaded = json.decodeFromString(ArgusSettings.serializer(), Files.readString(path))
        settings = loaded
        loadedPath = path
        logger.info("Loaded Argus config from ${path.toAbsolutePath()}")
        settings
    }.onFailure { ex ->
        logger.error("Failed to load Argus config: ${ex.message}", ex)
    }

    fun update(field: String, value: String): Result<ArgusSettings> = runCatching {
        val updated = when (field.lowercase()) {
            "bottoken" -> settings.copy(botToken = value)
            "guildid" -> settings.copy(guildId = value.toLongOrNull())
            "whitelistroleid" -> settings.copy(whitelistRoleId = value.toLongOrNull())
            "adminroleid" -> settings.copy(adminRoleId = value.toLongOrNull())
            "logchannelid" -> settings.copy(logChannelId = value.toLongOrNull())
            "applicationmessage" -> settings.copy(applicationMessage = value)
            "cachefile" -> settings.copy(cacheFile = value)
            else -> error("Unknown config field: $field")
        }
        val path = loadedPath
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(ArgusSettings.serializer(), updated))
        settings = updated
        logger.info("Updated Argus config field $field and saved to ${path.toAbsolutePath()}")
        settings
    }

    @JvmStatic
    @JvmName("updateJvm")
    fun updateJvm(field: String, value: String): Result<ArgusSettings> = update(field, value)

    @JvmStatic
    fun updateFromJava(field: String, value: String): Boolean = update(field, value).isSuccess
}
