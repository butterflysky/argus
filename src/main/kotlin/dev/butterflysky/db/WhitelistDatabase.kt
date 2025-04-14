package dev.butterflysky.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.util.*

/**
 * Database tables for whitelist management
 */
object WhitelistDatabase {
    private val logger = LoggerFactory.getLogger("argus-db")
    private var initialized = false
    private var dbFile: File? = null
    
    /**
     * Define database tables
     */
    object MinecraftUsers : UUIDTable("minecraft_users") {
        val username = varchar("username", 32)
        val isWhitelisted = bool("is_whitelisted").default(false)
        val addedAt = datetime("added_at").default(LocalDateTime.now())
        val addedBy = varchar("added_by", 128).nullable()
    }
    
    object DiscordUsers : UUIDTable("discord_users") {
        // Using string for Discord ID because it's a long number
        val discordId = varchar("discord_id", 64)
        val username = varchar("username", 128)
        val isAdmin = bool("is_admin").default(false)
        val isModerator = bool("is_moderator").default(false)
    }
    
    /**
     * One Discord user can have multiple Minecraft accounts
     */
    object UserMappings : IntIdTable("user_mappings") {
        val minecraftUser = reference("minecraft_user", MinecraftUsers)
        val discordUser = reference("discord_user", DiscordUsers)
        val createdAt = datetime("created_at").default(LocalDateTime.now())
        val createdBy = varchar("created_by", 128).nullable()
        val isPrimary = bool("is_primary").default(false)
        
        init {
            // Ensure a Minecraft user can only map to one Discord user, but
            // a Discord user can have multiple Minecraft accounts
            uniqueIndex("unique_minecraft_user", minecraftUser)
            
            // Ensure each Discord user has at most one "primary" Minecraft account
            index(false, isPrimary, discordUser)
        }
    }
    
    object UsernameHistory : IntIdTable("username_history") {
        val minecraftUser = reference("minecraft_user", MinecraftUsers).nullable()
        val discordUser = reference("discord_user", DiscordUsers).nullable()
        val oldName = varchar("old_name", 128)
        val newName = varchar("new_name", 128)
        val changedAt = datetime("changed_at").default(LocalDateTime.now())
        val platform = varchar("platform", 32) // "minecraft" or "discord"
    }
    
    object WhitelistEvents : IntIdTable("whitelist_events") {
        val minecraftUser = reference("minecraft_user", MinecraftUsers)
        val eventType = varchar("event_type", 32) // "add", "remove", etc.
        val timestamp = datetime("timestamp").default(LocalDateTime.now())
        val performedBy = varchar("performed_by", 128)
        val details = text("details").nullable()
    }
    
    /**
     * Initialize the database
     */
    fun initialize(runDirectory: File): Boolean {
        if (initialized) {
            return true
        }
        
        logger.info("Initializing database with run directory: ${runDirectory.absolutePath}")
        
        try {
            // Create whitelist database in run directory
            val dbDir = File(runDirectory.absolutePath + File.separator + "argus")
            logger.info("Database directory path: ${dbDir.absolutePath}")
            
            if (!dbDir.exists()) {
                logger.info("Creating directory: ${dbDir.absolutePath}")
                try {
                    val success = dbDir.mkdirs()
                    if (!success) {
                        logger.error("Failed to create directory despite no exception: ${dbDir.absolutePath}")
                        return false
                    }
                } catch (e: Exception) {
                    logger.error("Exception creating directory: ${dbDir.absolutePath}", e)
                    return false
                }
            }
            
            dbFile = File(dbDir, "whitelist.db")
            logger.info("Initializing whitelist database at ${dbFile?.absolutePath}")
            
            // Connect to database
            if (dbFile == null || !dbFile!!.parentFile.exists()) {
                logger.error("Database parent directory does not exist: ${dbFile?.parentFile?.absolutePath}")
                return false
            }
            
            // Use file:// protocol to ensure proper handling of absolute paths
            val jdbcUrl = "jdbc:sqlite:${dbFile!!.absolutePath}"
            logger.info("Connecting to database with URL: $jdbcUrl")
            Database.connect(jdbcUrl, "org.sqlite.JDBC")
            
            // Create tables if they don't exist
            transaction {
                SchemaUtils.create(
                    MinecraftUsers,
                    DiscordUsers,
                    UserMappings,
                    UsernameHistory,
                    WhitelistEvents
                )
            }
            
            initialized = true
            logger.info("Whitelist database initialized successfully")
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize whitelist database", e)
            return false
        }
    }
    
    /**
     * Check if the database is initialized
     */
    fun isInitialized(): Boolean {
        return initialized
    }
}