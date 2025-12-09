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
    val enforcementEnabled: Boolean = false,
    val cacheFile: String = "config/argus_db.json",
    val discordInviteUrl: String? = null,
)

object ArgusConfig {
    private val logger = LoggerFactory.getLogger("argus-config")
    private val json = Json { prettyPrint = true }
    private val defaultPath: Path =
        Paths.get(
            System.getProperty("argus.config.path")
                ?: System.getenv("ARGUS_CONFIG_PATH")
                ?: "config/argus.json",
        )

    @Volatile private var loadedPath: Path = defaultPath

    @Volatile
    private var settings: ArgusSettings = ArgusSettings()

    private data class ConfigField(
        val key: String,
        val sample: String,
        val description: String,
        val setter: (ArgusSettings, String) -> ArgusSettings,
        val getter: (ArgusSettings) -> String,
    )

    private val fields: List<ConfigField> =
        listOf(
            ConfigField("botToken", "abcdef.bot.token", "Discord bot token", { s, v -> s.copy(botToken = v) }, { it.botToken }),
            ConfigField(
                "guildId",
                "123456789012345678",
                "Discord guild id",
                { s, v -> s.copy(guildId = v.toLongOrNull() ?: error("guildId must be a number")) },
                { it.guildId?.toString() ?: "" },
            ),
            ConfigField(
                "whitelistRoleId",
                "234567890123456789",
                "Role that grants access",
                { s, v -> s.copy(whitelistRoleId = v.toLongOrNull() ?: error("whitelistRoleId must be a number")) },
                { it.whitelistRoleId?.toString() ?: "" },
            ),
            ConfigField(
                "adminRoleId",
                "345678901234567890",
                "Admins allowed to manage whitelist",
                { s, v -> s.copy(adminRoleId = v.toLongOrNull() ?: error("adminRoleId must be a number")) },
                { it.adminRoleId?.toString() ?: "" },
            ),
            ConfigField(
                "logChannelId",
                "456789012345678901",
                "Channel for audit messages",
                { s, v -> s.copy(logChannelId = v.toLongOrNull() ?: error("logChannelId must be a number")) },
                { it.logChannelId?.toString() ?: "" },
            ),
            ConfigField("applicationMessage", "Access Denied: Please apply in Discord.", "Login denial message", { s, v ->
                s.copy(
                    applicationMessage = v,
                )
            }, {
                it.applicationMessage
            }),
            ConfigField(
                "enforcementEnabled",
                "false",
                "Whether Argus enforces decisions (false = dry-run, log only)",
                { s, v -> s.copy(enforcementEnabled = v.equals("true", ignoreCase = true)) },
                { it.enforcementEnabled.toString() },
            ),
            ConfigField(
                "cacheFile",
                "config/argus_db.json",
                "Cache persistence path",
                { s, v -> s.copy(cacheFile = v) },
                { it.cacheFile },
            ),
            ConfigField(
                "discordInviteUrl",
                "https://discord.gg/yourserver",
                "Invite link shown in denial messages (optional)",
                { s, v -> s.copy(discordInviteUrl = v.ifBlank { null }) },
                { it.discordInviteUrl ?: "" },
            ),
        )

    private val fieldsMap = fields.associateBy { it.key.lowercase() }

    val cachePath: Path
        get() = Paths.get(settings.cacheFile)

    fun isConfigured(): Boolean =
        settings.botToken.isNotBlank() &&
            settings.guildId != null &&
            settings.whitelistRoleId != null &&
            settings.adminRoleId != null

    fun current(): ArgusSettings = settings

    fun load(path: Path = loadedPath): Result<ArgusSettings> =
        runCatching {
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

    fun update(
        field: String,
        value: String,
    ): Result<ArgusSettings> =
        runCatching {
            val meta = findField(field) ?: error("Unknown config field: $field")
            val updated = meta.setter(settings, value)
            val path = loadedPath
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(ArgusSettings.serializer(), updated))
            settings = updated
            logger.info("Updated Argus config field $field and saved to ${path.toAbsolutePath()}")
            settings
        }

    @JvmStatic
    @JvmName("updateJvm")
    fun updateJvm(
        field: String,
        value: String,
    ): Result<ArgusSettings> = update(field, value)

    @JvmStatic
    fun updateFromJava(
        field: String,
        value: String,
    ): Boolean = update(field, value).isSuccess

    fun get(field: String): Result<String> =
        runCatching {
            val meta = findField(field) ?: error("Unknown config field: $field")
            meta.getter(settings)
        }

    fun fieldNames(): List<String> = fields.map { it.key }

    fun sampleValue(field: String): String? = findField(field)?.sample

    private fun findField(field: String): ConfigField? = fieldsMap[field.lowercase()]

    @JvmStatic
    fun fieldNamesJvm(): List<String> = fieldNames()

    @JvmStatic
    fun sampleValueJvm(field: String): String? = sampleValue(field)

    @JvmStatic
    fun getValue(field: String): String? = get(field).getOrNull()
}
