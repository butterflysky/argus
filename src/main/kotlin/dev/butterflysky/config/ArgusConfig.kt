package dev.butterflysky.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Configuration handler for Argus mod
 */
class ArgusConfig {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-config")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private val configDir: Path = FabricLoader.getInstance().configDir
        private val configFile: File = configDir.resolve("argus.json").toFile()
        
        private var instance: ConfigData = ConfigData()
        
        /**
         * Load configuration from disk, creating default if it doesn't exist
         */
        fun load() {
            try {
                if (!configFile.exists()) {
                    logger.info("Config file not found, creating default at ${configFile.absolutePath}")
                    save()
                } else {
                    logger.info("Loading config from ${configFile.absolutePath}")
                    val loadedConfig = parseWithDefaults(ConfigData::class)
                    instance = loadedConfig
                    logger.info("Config loaded successfully")
                }
            } catch (e: Exception) {
                logger.error("Failed to load config, using defaults", e)
                instance = ConfigData()
            }
        }
        
        /**
         * Parse the config file, merging with defaults for any missing properties
         */
        private fun <T : Any> parseWithDefaults(configClass: KClass<T>): T {
            val defaultInstance = configClass.primaryConstructor?.callBy(emptyMap()) ?: 
                throw IllegalArgumentException("Config class must have a default constructor")
            
            if (!configFile.exists()) {
                return defaultInstance
            }
            
            val jsonElement = JsonParser.parseReader(FileReader(configFile))
            if (!jsonElement.isJsonObject) {
                logger.warn("Config file is not a JSON object, using defaults")
                return defaultInstance
            }
            
            val mergedJson = mergeWithDefaults(jsonElement.asJsonObject, defaultInstance)
            
            // If we found missing values during merging, save the updated config
            if (mergedJson != jsonElement) {
                logger.info("Found missing config values, updating config file with defaults")
                FileWriter(configFile).use { writer ->
                    gson.toJson(mergedJson, writer)
                }
            }
            
            return gson.fromJson(mergedJson, configClass.java)
        }
        
        /**
         * Recursively merge loaded JSON with defaults for any missing properties
         */
        private fun mergeWithDefaults(loadedJson: JsonObject, defaultInstance: Any): JsonElement {
            val defaultJson = gson.toJsonTree(defaultInstance)
            if (!defaultJson.isJsonObject) {
                return loadedJson
            }
            
            val result = loadedJson.deepCopy()
            var modified = false
            
            for (entry in defaultJson.asJsonObject.entrySet()) {
                val propertyName = entry.key
                val defaultValue = entry.value
                
                if (!result.has(propertyName)) {
                    // Property missing in loaded config, add it from defaults
                    result.add(propertyName, defaultValue)
                    modified = true
                    logger.debug("Added missing property '$propertyName' with default value")
                } else if (defaultValue.isJsonObject && result.get(propertyName).isJsonObject) {
                    // For nested objects, recursively merge
                    val propertyType = defaultInstance::class.memberProperties
                        .firstOrNull { it.name == propertyName }
                        ?.returnType?.classifier as? KClass<*>
                    
                    if (propertyType != null) {
                        val defaultPropertyInstance = propertyType.primaryConstructor?.callBy(emptyMap())
                        if (defaultPropertyInstance != null) {
                            val mergedProperty = mergeWithDefaults(
                                result.get(propertyName).asJsonObject,
                                defaultPropertyInstance
                            )
                            
                            if (mergedProperty != result.get(propertyName)) {
                                result.add(propertyName, mergedProperty)
                                modified = true
                            }
                        }
                    }
                }
            }
            
            return if (modified) result else loadedJson
        }
        
        /**
         * Save current configuration to disk
         */
        fun save() {
            try {
                if (!configFile.parentFile.exists()) {
                    Files.createDirectories(configFile.parentFile.toPath())
                }
                
                FileWriter(configFile).use { writer ->
                    gson.toJson(instance, writer)
                }
                logger.info("Config saved to ${configFile.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to save config", e)
            }
        }
        
        /**
         * Get the current config
         */
        fun get() = instance
        
        /**
         * Update the config with new values and save it to disk
         */
        fun update(newConfig: ConfigData) {
            instance = newConfig
            save()
        }
    }
    
    /**
     * Data class representing config values
     */
    data class ConfigData(
        val discord: DiscordConfig = DiscordConfig(),
        val reconnect: ReconnectConfig = ReconnectConfig(),
        val whitelist: WhitelistConfig = WhitelistConfig(),
        val link: LinkConfig = LinkConfig(),
        val timeouts: TimeoutConfig = TimeoutConfig(),
        val threadPools: ThreadPoolConfig = ThreadPoolConfig()
    )
    
    /**
     * Discord-specific configuration
     */
    data class DiscordConfig(
        val enabled: Boolean = false,
        val token: String = "YOUR_DISCORD_BOT_TOKEN",
        val guildId: String = "YOUR_DISCORD_GUILD_ID",
        val serverName: String = "Abfielder's Community Discord server",
        val adminRoles: List<String> = listOf("Admins", "Moderator"),
        val patronRole: String = "Patron",
        val adultRole: String = "Adults",
        val loggingChannel: String = "server-admin-messages"
    )
    
    /**
     * Reconnection settings
     */
    data class ReconnectConfig(
        val initialDelayMs: Long = 5000,
        val maxDelayMs: Long = 60000,
        val backoffMultiplier: Double = 1.5
    )
    
    /**
     * Whitelist-related configuration
     */
    data class WhitelistConfig(
        val cooldownHours: Long = 48,
        val defaultHistoryLimit: Int = 10,
        val defaultSearchLimit: Int = 20,
        val maxSearchLimit: Int = 50,
        val autoRemoveOnLeave: Boolean = false
    )
    
    /**
     * Account linking configuration
     */
    data class LinkConfig(
        val tokenExpiryMinutes: Long = 10
    )
    
    /**
     * Timeout configuration for various operations
     */
    data class TimeoutConfig(
        val profileLookupSeconds: Long = 5
    )
    
    /**
     * Thread pool configuration
     * 
     * Default values are optimized for a system with 20 vCPUs where:
     * - 1 vCPU is reserved for sysadmin tasks
     * - 2 vCPUs are reserved for MySQL server
     * - 1 vCPU is reserved for management console
     * - 1 vCPU is reserved for the main Minecraft thread
     * - ~5 vCPUs are used by Minecraft's internal workers
     * - ~10 vCPUs are available for our thread pools
     */
    data class ThreadPoolConfig(
        // Discord command executor - moderate capacity for handling user interactions
        // These are typically short-lived, responsive operations
        val discordCommandQueueSize: Int = 25,
        val discordCommandPoolSize: Int = 5,
        
        // Background task executor for bulk operations that may be CPU or I/O intensive
        // These are typically longer-running batch operations
        val backgroundTaskQueueSize: Int = 40,
        val backgroundTaskPoolSize: Int = 5,
        
        // Default for custom executors if not specified
        val defaultQueueSize: Int = 100
    )
}