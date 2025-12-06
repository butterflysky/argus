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

    private enum class FieldType { STRING, LONG, PATH }

    private data class FieldMeta(
        val key: String,
        val type: FieldType,
        val sample: String,
        val description: String
    )

    private val fields: List<FieldMeta> = listOf(
        FieldMeta("botToken", FieldType.STRING, "abcdef.bot.token", "Discord bot token"),
        FieldMeta("guildId", FieldType.LONG, "123456789012345678", "Discord guild id"),
        FieldMeta("whitelistRoleId", FieldType.LONG, "234567890123456789", "Role that grants access"),
        FieldMeta("adminRoleId", FieldType.LONG, "345678901234567890", "Admins allowed to manage whitelist"),
        FieldMeta("logChannelId", FieldType.LONG, "456789012345678901", "Channel for audit messages"),
        FieldMeta("applicationMessage", FieldType.STRING, "Access Denied: Please apply in Discord.", "Login denial message"),
        FieldMeta("cacheFile", FieldType.PATH, "config/argus_db.json", "Cache persistence path")
    )

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
        val meta = findField(field) ?: error("Unknown config field: $field")
        val updated = when (meta.key.lowercase()) {
            "bottoken" -> settings.copy(botToken = value)
            "guildid" -> settings.copy(guildId = value.toLongOrNull() ?: error("guildId must be a number"))
            "whitelistroleid" -> settings.copy(whitelistRoleId = value.toLongOrNull() ?: error("whitelistRoleId must be a number"))
            "adminroleid" -> settings.copy(adminRoleId = value.toLongOrNull() ?: error("adminRoleId must be a number"))
            "logchannelid" -> settings.copy(logChannelId = value.toLongOrNull() ?: error("logChannelId must be a number"))
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

    fun get(field: String): Result<String> = runCatching {
        val meta = findField(field) ?: error("Unknown config field: $field")
        when (meta.key.lowercase()) {
            "bottoken" -> settings.botToken
            "guildid" -> settings.guildId?.toString() ?: ""
            "whitelistroleid" -> settings.whitelistRoleId?.toString() ?: ""
            "adminroleid" -> settings.adminRoleId?.toString() ?: ""
            "logchannelid" -> settings.logChannelId?.toString() ?: ""
            "applicationmessage" -> settings.applicationMessage
            "cachefile" -> settings.cacheFile
            else -> error("Unknown config field: $field")
        }
    }

    fun fieldNames(): List<String> = fields.map { it.key }

    fun sampleValue(field: String): String? = findField(field)?.sample

    private fun findField(field: String): FieldMeta? =
        fields.firstOrNull { it.key.equals(field, ignoreCase = true) }

    @JvmStatic
    fun fieldNamesJvm(): List<String> = fieldNames()

    @JvmStatic
    fun sampleValueJvm(field: String): String? = sampleValue(field)

    @JvmStatic
    fun getValue(field: String): String? = get(field).getOrNull()
}
