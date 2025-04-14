package dev.butterflysky.service

import dev.butterflysky.db.WhitelistDatabase
import dev.butterflysky.db.WhitelistDatabase.DiscordUsers
import dev.butterflysky.db.WhitelistDatabase.MinecraftUsers
import dev.butterflysky.db.WhitelistDatabase.UserMappings
import dev.butterflysky.db.WhitelistDatabase.UsernameHistory
import dev.butterflysky.db.WhitelistDatabase.WhitelistEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.util.*
import com.mojang.authlib.GameProfile

/**
 * Service for managing whitelist operations
 */
class WhitelistService private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-whitelist")
        private val instance = WhitelistService()
        private var databaseConnected = false
        
        fun getInstance(): WhitelistService {
            return instance
        }
        
        fun isDatabaseConnected(): Boolean {
            return databaseConnected
        }
    }
    
    private var server: MinecraftServer? = null
    
    /**
     * Initialize the whitelist service
     */
    fun initialize(server: MinecraftServer): Boolean {
        this.server = server
        
        try {
            val runDirectory = server.getRunDirectory().toFile()
            logger.info("Server run directory: ${runDirectory.absolutePath}")
            
            if (!WhitelistDatabase.initialize(runDirectory)) {
                logger.error("Failed to initialize whitelist database")
                return false
            }
            
            // Import existing whitelist entries
            importExistingWhitelist()
            
            // Update database connection state
            databaseConnected = true
            
            logger.info("Whitelist service initialized successfully")
            return true
        } catch (e: Exception) {
            logger.error("Failed to initialize whitelist service", e)
            databaseConnected = false
            return false
        }
    }
    
    /**
     * Import existing whitelist entries from the vanilla whitelist
     */
    fun importExistingWhitelist() {
        val server = this.server ?: return
        
        try {
            // Import existing whitelist entries
            transaction {
                val playerManager = server.playerManager
                val whitelist = playerManager.whitelist
                
                // Use the names from the whitelist and get profiles from the user cache
                val names = whitelist.getNames()
                for (name in names) {
                    val profile = server.getUserCache()?.findByName(name)?.orElse(null)
                    if (profile != null) {
                        val uuid = profile.id
                        val username = profile.name
                        
                        // Check if this player already exists in our database
                        val existingPlayer = MinecraftUsers.selectAll().where { MinecraftUsers.id eq uuid }.firstOrNull()
                        
                        if (existingPlayer == null) {
                            // Add the player to our database
                            MinecraftUsers.insert {
                                it[id] = uuid
                                it[MinecraftUsers.username] = username
                                it[isWhitelisted] = true
                                it[addedAt] = LocalDateTime.now()
                                it[addedBy] = "import"
                            }
                            
                            // Log the import
                            WhitelistEvents.insert {
                                it[minecraftUser] = uuid
                                it[eventType] = "import"
                                it[timestamp] = LocalDateTime.now()
                                it[performedByDiscordId] = null // System action, no Discord user
                                it[details] = "Imported from vanilla whitelist"
                            }
                            
                            logger.info("Imported whitelist entry for $username ($uuid)")
                        } else {
                            // Update existing entry
                            MinecraftUsers.update({ MinecraftUsers.id eq uuid }) {
                                it[isWhitelisted] = true
                            }
                            
                            logger.info("Updated whitelist status for $username ($uuid)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error importing existing whitelist entries", e)
        }
    }
    
    /**
     * Get all whitelisted Minecraft users
     */
    fun getWhitelistedPlayers(): List<MinecraftUserInfo> {
        return transaction {
            MinecraftUsers.selectAll().where { MinecraftUsers.isWhitelisted eq true }
                .map { 
                    MinecraftUserInfo(
                        uuid = it[MinecraftUsers.id].value,
                        username = it[MinecraftUsers.username],
                        addedAt = it[MinecraftUsers.addedAt],
                        addedBy = it[MinecraftUsers.addedBy] ?: "unknown"
                    )
                }
        }
    }
    
    /**
     * Add a Minecraft user to the whitelist
     */
    fun addToWhitelist(uuid: UUID, username: String, discordId: String): Boolean {
        try {
            val server = this.server ?: return false
            
            // Add to database
            transaction {
                // Check if this player already exists
                val existingPlayer = MinecraftUsers.selectAll().where { MinecraftUsers.id eq uuid }.firstOrNull()
                
                if (existingPlayer == null) {
                    // Add new player
                    MinecraftUsers.insert {
                        it[id] = uuid
                        it[MinecraftUsers.username] = username
                        it[isWhitelisted] = true
                        it[addedAt] = LocalDateTime.now()
                        it[MinecraftUsers.addedBy] = discordId
                    }
                } else {
                    // Update existing player
                    MinecraftUsers.update({ MinecraftUsers.id eq uuid }) {
                        it[isWhitelisted] = true
                        // Check if username has changed
                        if (existingPlayer[MinecraftUsers.username] != username) {
                            val oldName = existingPlayer[MinecraftUsers.username]
                            it[MinecraftUsers.username] = username
                            
                            // Record username change
                            UsernameHistory.insert {
                                it[minecraftUser] = uuid
                                // discordUser is nullable, so we don't need to explicitly set it
                                it[UsernameHistory.oldName] = oldName
                                it[UsernameHistory.newName] = username
                                it[changedAt] = LocalDateTime.now()
                                it[platform] = "minecraft"
                            }
                        }
                    }
                }
                
                // Log the event
                WhitelistEvents.insert {
                    it[minecraftUser] = uuid
                    it[eventType] = "add"
                    it[timestamp] = LocalDateTime.now()
                    it[performedByDiscordId] = discordId
                    it[details] = null
                }
            }
            
            // Also add to the vanilla whitelist
            try {
                val profile = com.mojang.authlib.GameProfile(uuid, username)
                val entry = net.minecraft.server.WhitelistEntry(profile)
                server.playerManager.whitelist.add(entry)
            } catch (e: Exception) {
                logger.error("Error adding to vanilla whitelist: ${e.message}")
            }
            
            logger.info("Added $username ($uuid) to whitelist by Discord user $discordId")
            return true
        } catch (e: Exception) {
            logger.error("Error adding $username ($uuid) to whitelist", e)
            return false
        }
    }
    
    /**
     * Remove a Minecraft user from the whitelist
     */
    fun removeFromWhitelist(uuid: UUID, discordId: String): Boolean {
        try {
            val server = this.server ?: return false
            
            // Remove from database
            transaction {
                MinecraftUsers.update({ MinecraftUsers.id eq uuid }) {
                    it[isWhitelisted] = false
                }
                
                // Log the event
                WhitelistEvents.insert {
                    it[minecraftUser] = uuid
                    it[eventType] = "remove"
                    it[timestamp] = LocalDateTime.now()
                    it[performedByDiscordId] = discordId
                    it[details] = null
                }
            }
            
            // Also remove from the vanilla whitelist
            val playerManager = server.playerManager
            val user = playerManager.server.getUserCache()?.getByUuid(uuid)?.orElse(null)
            if (user != null) {
                try {
                    val entry = net.minecraft.server.WhitelistEntry(user)
                    playerManager.whitelist.remove(entry)
                } catch (e: Exception) {
                    logger.error("Error removing from vanilla whitelist: ${e.message}")
                }
            }
            
            logger.info("Removed player $uuid from whitelist by Discord user $discordId")
            return true
        } catch (e: Exception) {
            logger.error("Error removing $uuid from whitelist", e)
            return false
        }
    }
    
    /**
     * Check if a Minecraft user is whitelisted
     */
    fun isWhitelisted(uuid: UUID): Boolean {
        return transaction {
            MinecraftUsers.selectAll().where { 
                (MinecraftUsers.id eq uuid) and (MinecraftUsers.isWhitelisted eq true)
            }.count() > 0
        }
    }
    
    /**
     * Find a Minecraft user by username (case insensitive)
     */
    fun findMinecraftUserByName(username: String): MinecraftUserInfo? {
        return transaction {
            MinecraftUsers.selectAll().where {
                MinecraftUsers.username.lowerCase() eq username.lowercase()
            }.firstOrNull()?.let {
                MinecraftUserInfo(
                    uuid = it[MinecraftUsers.id].value,
                    username = it[MinecraftUsers.username],
                    addedAt = it[MinecraftUsers.addedAt],
                    addedBy = it[MinecraftUsers.addedBy] ?: "unknown"
                )
            }
        }
    }
    
    /**
     * Map a Discord user to a Minecraft user
     */
    fun mapDiscordToMinecraft(
        discordUserId: String, 
        discordUsername: String,
        minecraftUuid: UUID,
        minecraftUsername: String,
        createdByDiscordId: String,
        isPrimary: Boolean = false
    ): Boolean {
        try {
            transaction {
                // Ensure both users exist
                val discordUserExists = DiscordUsers.selectAll().where {
                    DiscordUsers.discordId eq discordUserId
                }.count() > 0
                
                val discordUuid = if (!discordUserExists) {
                    val uuid = UUID.randomUUID()
                    DiscordUsers.insert {
                        it[id] = uuid
                        it[DiscordUsers.discordId] = discordUserId
                        it[username] = discordUsername
                    }
                    uuid
                } else {
                    DiscordUsers.selectAll().where {
                        DiscordUsers.discordId eq discordUserId
                    }.first()[DiscordUsers.id].value
                }
                
                val minecraftUserExists = MinecraftUsers.selectAll().where {
                    MinecraftUsers.id eq minecraftUuid
                }.count() > 0
                
                if (!minecraftUserExists) {
                    MinecraftUsers.insert {
                        it[id] = minecraftUuid
                        it[username] = minecraftUsername
                        it[isWhitelisted] = true
                        it[addedAt] = LocalDateTime.now()
                        it[addedBy] = createdByDiscordId
                    }
                }
                
                // If making this primary, clear any other primary mappings for this Discord user
                if (isPrimary) {
                    UserMappings.update({
                        (UserMappings.discordUser eq discordUuid) and
                        (UserMappings.isPrimary eq true)
                    }) {
                        it[UserMappings.isPrimary] = false
                    }
                }
                
                // Create mapping if it doesn't exist
                val mappingExists = UserMappings.selectAll().where {
                    (UserMappings.minecraftUser eq minecraftUuid) and
                    (UserMappings.discordUser eq discordUuid)
                }.count() > 0
                
                if (!mappingExists) {
                    UserMappings.insert {
                        it[minecraftUser] = minecraftUuid
                        it[discordUser] = discordUuid
                        it[createdAt] = LocalDateTime.now()
                        it[UserMappings.createdBy] = createdByDiscordId
                        it[UserMappings.isPrimary] = isPrimary
                    }
                } else {
                    // Update existing mapping
                    UserMappings.update({
                        (UserMappings.minecraftUser eq minecraftUuid) and
                        (UserMappings.discordUser eq discordUuid)
                    }) {
                        it[UserMappings.isPrimary] = isPrimary
                    }
                }
            }
            
            logger.info("Mapped Discord user $discordUsername ($discordUserId) to Minecraft user $minecraftUsername ($minecraftUuid) by Discord user $createdByDiscordId")
            return true
        } catch (e: Exception) {
            logger.error("Error mapping Discord user to Minecraft user", e)
            return false
        }
    }
    
    /**
     * Get all Minecraft accounts for a Discord user
     */
    fun getMinecraftAccountsForDiscordUser(discordUserId: String): List<MinecraftUserInfo> {
        return transaction {
            (UserMappings innerJoin MinecraftUsers innerJoin DiscordUsers)
                .selectAll().where { DiscordUsers.discordId eq discordUserId }
                .map {
                    MinecraftUserInfo(
                        uuid = it[MinecraftUsers.id].value,
                        username = it[MinecraftUsers.username],
                        addedAt = it[MinecraftUsers.addedAt],
                        addedBy = it[MinecraftUsers.addedBy] ?: "unknown",
                        isPrimary = it[UserMappings.isPrimary]
                    )
                }
        }
    }
    
    /**
     * Get the Discord user for a Minecraft account
     */
    fun getDiscordUserForMinecraftAccount(minecraftUuid: UUID): DiscordUserInfo? {
        return transaction {
            (UserMappings innerJoin MinecraftUsers innerJoin DiscordUsers)
                .selectAll().where { MinecraftUsers.id eq minecraftUuid }
                .firstOrNull()?.let {
                    DiscordUserInfo(
                        uuid = it[DiscordUsers.id].value,
                        discordId = it[DiscordUsers.discordId],
                        username = it[DiscordUsers.username],
                        isAdmin = it[DiscordUsers.isAdmin],
                        isModerator = it[DiscordUsers.isModerator]
                    )
                }
        }
    }
    
    /**
     * Unlink a specific Discord user from a Minecraft account
     */
    fun unlinkDiscordMinecraft(discordUserId: String, minecraftName: String, performedByDiscordId: String): Boolean {
        try {
            // Find the Minecraft user
            val minecraftUser = findMinecraftUserByName(minecraftName) ?: return false
            
            transaction {
                // Find the Discord user
                val discordUserUuid = DiscordUsers.selectAll().where { 
                    DiscordUsers.discordId eq discordUserId 
                }.firstOrNull()?.get(DiscordUsers.id)?.value ?: return@transaction
                
                // Delete the mapping
                UserMappings.deleteWhere { 
                    (UserMappings.minecraftUser.eq(minecraftUser.uuid)) and 
                    (UserMappings.discordUser.eq(discordUserUuid))
                }
                
                logger.info("Unlinked Discord user $discordUserId from Minecraft account $minecraftName by Discord user $performedByDiscordId")
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Error unlinking accounts", e)
            return false
        }
    }
    
    /**
     * Unlink all Minecraft accounts from a Discord user
     */
    fun unlinkAllMinecraftAccounts(discordUserId: String, performedByDiscordId: String): Boolean {
        try {
            transaction {
                // Find the Discord user
                val discordUserUuid = DiscordUsers.selectAll().where { 
                    DiscordUsers.discordId eq discordUserId 
                }.firstOrNull()?.get(DiscordUsers.id)?.value ?: return@transaction
                
                // Delete all mappings
                UserMappings.deleteWhere { 
                    UserMappings.discordUser.eq(discordUserUuid)
                }
                
                logger.info("Unlinked all Minecraft accounts from Discord user $discordUserId by Discord user $performedByDiscordId")
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Error unlinking accounts", e)
            return false
        }
    }
    
    /**
     * Unlink a Minecraft account from all Discord users
     */
    fun unlinkMinecraftAccount(minecraftName: String, performedByDiscordId: String): Boolean {
        try {
            // Find the Minecraft user
            val minecraftUser = findMinecraftUserByName(minecraftName) ?: return false
            
            transaction {
                // Delete all mappings
                UserMappings.deleteWhere { 
                    UserMappings.minecraftUser.eq(minecraftUser.uuid)
                }
                
                logger.info("Unlinked Minecraft account $minecraftName from all Discord users by Discord user $performedByDiscordId")
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Error unlinking accounts", e)
            return false
        }
    }
    
    /**
     * Get whitelist history for a specific player
     */
    fun getWhitelistHistory(minecraftUuid: UUID, limit: Int = 10): List<WhitelistEventInfo> {
        return transaction {
            WhitelistEvents.selectAll().where { 
                WhitelistEvents.minecraftUser eq minecraftUuid 
            }
            .orderBy(WhitelistEvents.timestamp to SortOrder.DESC)
            .limit(limit)
            .map { 
                WhitelistEventInfo(
                    playerUuid = minecraftUuid,
                    playerName = MinecraftUsers.selectAll().where { MinecraftUsers.id eq minecraftUuid }
                        .firstOrNull()?.get(MinecraftUsers.username) ?: "Unknown",
                    eventType = it[WhitelistEvents.eventType],
                    timestamp = it[WhitelistEvents.timestamp],
                    performedByDiscordId = it[WhitelistEvents.performedByDiscordId],
                    details = it[WhitelistEvents.details]
                )
            }
        }
    }
    
    /**
     * Get recent whitelist events across all players
     */
    fun getRecentWhitelistHistory(limit: Int = 10): List<WhitelistEventInfo> {
        return transaction {
            (WhitelistEvents innerJoin MinecraftUsers)
            .selectAll()
            .orderBy(WhitelistEvents.timestamp to SortOrder.DESC)
            .limit(limit)
            .map { 
                WhitelistEventInfo(
                    playerUuid = it[WhitelistEvents.minecraftUser].value,
                    playerName = it[MinecraftUsers.username],
                    eventType = it[WhitelistEvents.eventType],
                    timestamp = it[WhitelistEvents.timestamp],
                    performedByDiscordId = it[WhitelistEvents.performedByDiscordId],
                    details = it[WhitelistEvents.details]
                )
            }
        }
    }
    
    /**
     * Get whitelist history for a player by Minecraft username
     */
    fun getWhitelistHistoryByMinecraftName(minecraftName: String, limit: Int = 10): List<WhitelistEventInfo> {
        // Find the player by username
        val player = findMinecraftUserByName(minecraftName) ?: return emptyList()
        
        // Get history using the UUID
        return getWhitelistHistory(player.uuid, limit)
    }
    
    /**
     * Get whitelist history for all Minecraft accounts linked to a Discord user
     */
    fun getWhitelistHistoryByDiscordId(discordUserId: String, limit: Int = 10): List<WhitelistEventInfo> {
        // Get all Minecraft accounts for this Discord user
        val minecraftAccounts = getMinecraftAccountsForDiscordUser(discordUserId)
        if (minecraftAccounts.isEmpty()) {
            return emptyList()
        }
        
        // Collect history for all accounts
        return transaction {
            (WhitelistEvents innerJoin MinecraftUsers)
            .selectAll().where { WhitelistEvents.minecraftUser inList minecraftAccounts.map { it.uuid } }
            .orderBy(WhitelistEvents.timestamp to SortOrder.DESC)
            .limit(limit)
            .map { 
                WhitelistEventInfo(
                    playerUuid = it[WhitelistEvents.minecraftUser].value,
                    playerName = it[MinecraftUsers.username],
                    eventType = it[WhitelistEvents.eventType],
                    timestamp = it[WhitelistEvents.timestamp],
                    performedByDiscordId = it[WhitelistEvents.performedByDiscordId],
                    details = it[WhitelistEvents.details]
                )
            }
        }
    }
    
    /**
     * Check if a Minecraft username is already on the whitelist
     */
    fun isUsernameWhitelisted(username: String): Boolean {
        return transaction {
            MinecraftUsers.selectAll().where { 
                (MinecraftUsers.username.lowerCase() eq username.lowercase()) and 
                (MinecraftUsers.isWhitelisted eq true)
            }.count() > 0
        }
    }
}

/**
 * Minecraft user information
 */
data class MinecraftUserInfo(
    val uuid: UUID,
    val username: String,
    val addedAt: LocalDateTime,
    val addedBy: String,
    val isPrimary: Boolean = false
)

/**
 * Discord user information
 */
data class DiscordUserInfo(
    val uuid: UUID,
    val discordId: String,
    val username: String,
    val isAdmin: Boolean,
    val isModerator: Boolean
)

/**
 * Whitelist event information
 */
data class WhitelistEventInfo(
    val playerUuid: UUID,
    val playerName: String,
    val eventType: String,
    val timestamp: LocalDateTime,
    val performedByDiscordId: String?,
    val details: String?
)