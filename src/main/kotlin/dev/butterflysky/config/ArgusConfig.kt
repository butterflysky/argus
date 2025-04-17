package dev.butterflysky.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path

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
                    FileReader(configFile).use { reader ->
                        instance = gson.fromJson(reader, ConfigData::class.java)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to load config, using defaults", e)
                instance = ConfigData()
            }
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
        val timeouts: TimeoutConfig = TimeoutConfig()
    )
    
    /**
     * Discord-specific configuration
     */
    data class DiscordConfig(
        val enabled: Boolean = false,
        val token: String = "YOUR_DISCORD_BOT_TOKEN",
        val guildId: String = "YOUR_DISCORD_GUILD_ID",
        val adminRoles: List<String> = listOf("Admins", "Moderator"),
        val patronRole: String = "Patron",
        val adultRole: String = "Adults"
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
        val maxSearchLimit: Int = 50
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
}