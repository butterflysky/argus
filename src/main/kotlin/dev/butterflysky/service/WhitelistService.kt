package dev.butterflysky.service

import dev.butterflysky.db.WhitelistDatabase
import dev.butterflysky.db.WhitelistDatabase.DiscordUser
import dev.butterflysky.db.WhitelistDatabase.MinecraftUser
import dev.butterflysky.db.WhitelistDatabase.WhitelistApplication
import dev.butterflysky.db.WhitelistDatabase.ApplicationStatus
import dev.butterflysky.db.WhitelistDatabase.NameType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import dev.butterflysky.config.ArgusConfig
import java.io.File
import java.time.Instant
import java.util.*
import com.mojang.authlib.GameProfile
import net.minecraft.util.Uuids
import java.time.format.DateTimeFormatter
import org.jetbrains.exposed.dao.id.EntityID
import net.minecraft.server.PlayerManager
import net.minecraft.server.Whitelist
import java.util.concurrent.TimeUnit

/**
 * Service for managing whitelist operations
 */
class WhitelistService private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-whitelist")
        private val instance = WhitelistService()
        private var databaseConnected = false
        
        fun getInstance() = instance
        
        fun isDatabaseConnected() = databaseConnected
        
        /**
         * Run a database transaction with error handling and logging
         * 
         * @param errorMessage Error message prefix to include in logs
         * @param defaultValue Default value to return if transaction fails
         * @param block Transaction code to execute
         */
        private inline fun <T> dbTransaction(
            errorMessage: String,
            defaultValue: T,
            crossinline block: Transaction.() -> T
        ): T {
            return try {
                transaction {
                    block()
                }
            } catch (e: Exception) {
                logger.error("$errorMessage: ${e.message}", e)
                defaultValue
            }
        }
    }
    
    private var server: MinecraftServer? = null
    
    /**
     * Check if the server is available
     */
    private fun validateServerAvailable(): Boolean {
        return if (server == null) {
            logger.error("Server is not available")
            false
        } else true
    }
    
    /**
     * Get or create a Discord user with improved error handling
     * 
     * @param discordId The Discord user ID
     * @param discordUsername Optional Discord username, defaults to "Unknown" if creating new
     * @return The Discord user entity or null if creation fails
     */
    private fun getOrCreateDiscordUser(discordId: String, discordUsername: String? = null): DiscordUser? {
        try {
            val id = discordId.toLongOrNull() ?: return null
            
            return dbTransaction("Failed to get or create Discord user", null) {
                DiscordUser.findById(id) ?: DiscordUser.new(id) {
                    currentUsername = discordUsername ?: "Unknown"
                    currentServername = null
                    joinedServerAt = Instant.now()
                    isInServer = true
                }
            }
        } catch (e: Exception) {
            logger.error("Error creating Discord user for ID $discordId: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get or create a Minecraft user with improved error handling
     * 
     * @param uuid The Minecraft user UUID
     * @param username The Minecraft username
     * @param owner Optional Discord user owner, can be null for unmapped users
     * @return The Minecraft user entity or null if creation fails
     */
    private fun getOrCreateMinecraftUser(uuid: UUID, username: String, owner: DiscordUser? = null): MinecraftUser? {
        try {
            return dbTransaction("Failed to get or create Minecraft user", null) {
                MinecraftUser.findById(uuid) ?: MinecraftUser.new(uuid) {
                    currentUsername = username
                    currentOwner = owner
                }
            }
        } catch (e: Exception) {
            logger.error("Error creating Minecraft user for $username ($uuid): ${e.message}", e)
            return null
        }
    }
    
    /**
     * Update a Minecraft username if it changed
     */
    private fun updateMinecraftUsername(user: MinecraftUser, newUsername: String, recordedBy: DiscordUser): Boolean {
        return dbTransaction("Failed to update Minecraft username", false) {
            if (user.currentUsername != newUsername) {
                val oldUsername = user.currentUsername
                user.currentUsername = newUsername
                
                // Add to username history
                WhitelistDatabase.MinecraftUserName.new {
                    this.minecraftUser = user
                    this.username = newUsername
                    this.recordedBy = recordedBy
                }
                
                logger.info("Updated Minecraft username for ${user.id.value} from $oldUsername to $newUsername")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Find an approved whitelist application for a Minecraft user
     */
    private fun findApprovedApplication(minecraftUser: MinecraftUser): WhitelistApplication? {
        return dbTransaction("Failed to find approved application", null) {
            WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
            }.sortedByDescending { it.appliedAt }.firstOrNull()
        }
    }
    
    /**
     * Check if a Discord user ID is the unmapped placeholder ID
     */
    private fun isUnmappedDiscordUser(discordUser: DiscordUser?) = 
        discordUser == null || discordUser.id.value == WhitelistDatabase.UNMAPPED_DISCORD_ID
    
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
            // System user to record audit logs
            val systemUser = transaction { DiscordUser.getSystemUser() }
            
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
                        val existingPlayer = MinecraftUser.findById(uuid)
                        
                        if (existingPlayer == null) {
                            // Import as a legacy entry with no Discord mapping
                            val result = WhitelistDatabase.importLegacyMinecraftUser(
                                minecraftUuid = uuid,
                                username = username,
                                performedBy = systemUser
                            )
                            
                            logger.info("Imported whitelist entry for $username ($uuid)")
                        } else {
                            // Player exists, check if there's a whitelist application
                            val hasApplication = WhitelistApplication.find {
                                (WhitelistDatabase.WhitelistApplications.minecraftUser eq existingPlayer.id) and
                                (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                            }.count() > 0
                            
                            if (!hasApplication) {
                                // Create a legacy whitelist entry for this player
                                WhitelistApplication.createLegacyWhitelist(
                                    minecraftUser = existingPlayer,
                                    moderator = systemUser,
                                    notes = "Imported from vanilla whitelist"
                                )
                                
                                logger.info("Created legacy whitelist application for $username ($uuid)")
                            }
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
            // Find all approved whitelist applications
            WhitelistApplication.find {
                WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED
            }.map { application ->
                val minecraftUser = application.minecraftUser
                val discordUser = application.discordUser
                val processedBy = application.processedBy
                
                MinecraftUserInfo(
                    uuid = minecraftUser.id.value,
                    username = minecraftUser.currentUsername,
                    addedAt = application.appliedAt,
                    addedBy = processedBy?.id?.value?.toString() ?: "system",
                    discordUserId = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                        discordUser.id.value.toString() else null,
                    discordUsername = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                        discordUser.currentUsername else null
                )
            }
        }
    }
    
    /**
     * Add a Minecraft user to the whitelist
     */
    fun addToWhitelist(uuid: UUID, username: String, discordId: String, override: Boolean = false, reason: String? = null): Boolean {
        try {
            val server = this.server ?: return false
            
            // Get the Discord user or create a new one
            val discordUser = transaction {
                DiscordUser.findById(discordId.toLong()) ?: DiscordUser.new(discordId.toLong()) {
                    currentUsername = "Unknown"
                    currentServername = null
                    joinedServerAt = Instant.now()
                    isInServer = true
                }
            }
            
            // Get or create Minecraft user
            val minecraftUser = transaction {
                MinecraftUser.findById(uuid) ?: MinecraftUser.new(uuid) {
                    currentUsername = username
                    currentOwner = discordUser
                }
            }
            
            // Record username if it changed
            val usernameChanged = transaction {
                if (minecraftUser.currentUsername != username) {
                    minecraftUser.currentUsername = username
                    
                    // Add to username history
                    WhitelistDatabase.MinecraftUserName.new {
                        this.minecraftUser = minecraftUser
                        this.username = username
                        this.recordedBy = discordUser
                    }
                    true
                } else {
                    false
                }
            }
            
            if (usernameChanged) {
                logger.info("Updated Minecraft username for $uuid to $username")
            }
            
            // First, check if player is already whitelisted
            val alreadyWhitelisted = transaction {
                WhitelistApplication.find {
                    (WhitelistDatabase.WhitelistApplications.minecraftUser eq EntityID(uuid, WhitelistDatabase.MinecraftUsers)) and
                    (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                }.firstOrNull() != null
            }
            
            if (alreadyWhitelisted) {
                logger.info("Player $username ($uuid) is already whitelisted")
                return true
            }
            
            // Add to vanilla whitelist first to ensure it succeeds
            try {
                val profile = com.mojang.authlib.GameProfile(uuid, username)
                val entry = net.minecraft.server.WhitelistEntry(profile)
                server.playerManager.whitelist.add(entry)
            } catch (e: Exception) {
                logger.error("Error adding to vanilla whitelist: ${e.message}", e)
                // If vanilla whitelist update fails, abort the whole operation
                return false
            }
            
            // If vanilla whitelist succeeded, create our database entry
            try {
                transaction {
                    // Create a whitelist entry directly added by moderator
                    WhitelistApplication.createModeratorWhitelist(
                        discordUser = discordUser,
                        minecraftUser = minecraftUser,
                        moderator = discordUser,
                        overrideReason = reason ?: "Added by moderator",
                        notes = null
                    )
                    
                    // Create audit log
                    WhitelistDatabase.createAuditLog(
                        actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD,
                        entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                        entityId = uuid.toString(),
                        performedBy = discordUser,
                        details = "Added $username to whitelist by moderator action"
                    )
                }
                
                logger.info("Player $username ($uuid) whitelisted directly by moderator $discordId")
                return true
            } catch (e: Exception) {
                // Database operation failed after vanilla whitelist succeeded
                // Try to revert vanilla whitelist change
                logger.error("Database operation failed after vanilla whitelist update. Attempting to revert...", e)
                try {
                    val profile = com.mojang.authlib.GameProfile(uuid, username)
                    val entry = net.minecraft.server.WhitelistEntry(profile)
                    server.playerManager.whitelist.remove(entry)
                } catch (revertError: Exception) {
                    logger.error("Failed to revert vanilla whitelist change. System may be in inconsistent state!", revertError)
                }
                return false
            }
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
            
            // Get the Discord user
            val discordUser = transaction {
                DiscordUser.findById(discordId.toLong())
            } ?: return false
            
            // Find the Minecraft user
            val minecraftUser = transaction {
                MinecraftUser.findById(uuid)
            } ?: return false
            
            // Check if the player is actually whitelisted
            val applications = transaction {
                WhitelistApplication.find {
                    (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                    (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                }.toList()
            }
            
            if (applications.isEmpty()) {
                logger.info("Player ${minecraftUser.currentUsername} ($uuid) is not whitelisted, nothing to remove")
                return false
            }
            
            // Remove from vanilla whitelist first
            val playerManager = server.playerManager
            val user = playerManager.server.getUserCache()?.getByUuid(uuid)?.orElse(null)
            if (user != null) {
                try {
                    val entry = net.minecraft.server.WhitelistEntry(user)
                    playerManager.whitelist.remove(entry)
                } catch (e: Exception) {
                    logger.error("Error removing from vanilla whitelist: ${e.message}", e)
                    // If we can't remove from vanilla whitelist, abort
                    return false
                }
            } else {
                logger.warn("Could not find user profile for $uuid in user cache - removing from database only")
            }
            
            // Now update our database records
            try {
                transaction {
                    // Mark all approved applications as removed
                    applications.forEach { application ->
                        application.status = ApplicationStatus.REMOVED
                        application.processedAt = Instant.now()
                        application.processedBy = discordUser
                    }
                    
                    // Create audit log
                    WhitelistDatabase.createAuditLog(
                        actionType = WhitelistDatabase.AuditActionType.WHITELIST_REMOVE,
                        entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                        entityId = uuid.toString(),
                        performedBy = discordUser,
                        details = "Removed ${minecraftUser.currentUsername} from whitelist by moderator action"
                    )
                }
                
                logger.info("Player ${minecraftUser.currentUsername} ($uuid) removed from whitelist by moderator $discordId")
                return true
            } catch (e: Exception) {
                // If database update fails but vanilla removal succeeded, try to add back to vanilla whitelist
                logger.error("Database operation failed after vanilla whitelist removal. Attempting to revert...", e)
                if (user != null) {
                    try {
                        val entry = net.minecraft.server.WhitelistEntry(user)
                        playerManager.whitelist.add(entry)
                    } catch (revertError: Exception) {
                        logger.error("Failed to revert vanilla whitelist removal. System may be in inconsistent state!", revertError)
                    }
                }
                return false
            }
        } catch (e: Exception) {
            logger.error("Error removing $uuid from whitelist", e)
            return false
        }
    }
    
    /**
     * Map a Discord user to a Minecraft account
     */
    fun mapDiscordToMinecraft(
        discordUserId: String,
        discordUsername: String,
        minecraftUuid: UUID,
        minecraftUsername: String,
        createdByDiscordId: String
    ): Boolean {
        try {
            // Get or create the Discord user
            val discordUser = transaction {
                DiscordUser.findById(discordUserId.toLong()) ?: DiscordUser.new(discordUserId.toLong()) {
                    currentUsername = discordUsername
                    currentServername = null
                    joinedServerAt = Instant.now()
                    isInServer = true
                }
            }
            
            // Record username if it changed
            transaction {
                if (discordUser.currentUsername != discordUsername) {
                    val oldUsername = discordUser.currentUsername
                    discordUser.currentUsername = discordUsername
                    
                    // Add to username history
                    WhitelistDatabase.DiscordUserName.new {
                        this.discordUser = discordUser
                        this.username = discordUsername
                        this.type = NameType.USERNAME
                        this.recordedAt = Instant.now()
                        this.recordedBy = discordUser
                    }
                    
                    logger.info("Updated Discord username for user ID $discordUserId from $oldUsername to $discordUsername")
                }
            }
            
            // Get or create the Minecraft user
            val minecraftUser = transaction {
                MinecraftUser.findById(minecraftUuid) ?: MinecraftUser.new(minecraftUuid) {
                    currentUsername = minecraftUsername
                    currentOwner = discordUser
                }
            }
            
            // Record username if it changed
            transaction {
                if (minecraftUser.currentUsername != minecraftUsername) {
                    val oldUsername = minecraftUser.currentUsername
                    minecraftUser.currentUsername = minecraftUsername
                    
                    // Add to username history
                    WhitelistDatabase.MinecraftUserName.new {
                        this.minecraftUser = minecraftUser
                        this.username = minecraftUsername
                        this.recordedBy = discordUser
                    }
                    
                    logger.info("Updated Minecraft username for $minecraftUuid from $oldUsername to $minecraftUsername")
                }
            }
            
            // Set the Minecraft user's owner to the Discord user
            transaction {
                // Get the current owner
                val currentOwner = minecraftUser.currentOwner
                
                // Update owner if different
                if (currentOwner?.id?.value != discordUserId.toLong()) {
                    minecraftUser.currentOwner = discordUser
                    minecraftUser.transferredAt = Instant.now()
                    
                    // Create audit log for ownership change
                    WhitelistDatabase.createAuditLog(
                        actionType = WhitelistDatabase.AuditActionType.ACCOUNT_LINK,
                        entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                        entityId = minecraftUuid.toString(),
                        performedBy = discordUser,
                        details = "Linked Minecraft account $minecraftUsername to Discord user $discordUsername ($discordUserId)"
                    )
                    
                    logger.info("Set Discord user $discordUsername ($discordUserId) as owner of Minecraft account $minecraftUsername ($minecraftUuid)")
                }
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Error mapping Discord user to Minecraft user", e)
            return false
        }
    }
    
    /**
     * Submit a new whitelist application
     */
    fun submitWhitelistApplication(discordId: String, minecraftUsername: String): ApplicationResult {
        try {
            // Validate the Minecraft username via Mojang API
            val server = this.server ?: return ApplicationResult.Error("Server not available")
            
            // Get player profile from username
            val profile = getGameProfile(minecraftUsername) 
                ?: return ApplicationResult.Error("Could not find Minecraft player: $minecraftUsername")
            
            // Get or create the Discord user
            val discordUser = transaction {
                DiscordUser.findById(discordId.toLong()) ?: DiscordUser.new(discordId.toLong()) {
                    currentUsername = "Unknown" // Will be updated by Discord events
                    currentServername = null
                    joinedServerAt = Instant.now()
                    isInServer = true
                }
            }
            
            // Check if user already has a pending application
            val hasPendingApplication = transaction {
                WhitelistApplication.find {
                    (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                    (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.PENDING)
                }.count() > 0
            }
            
            if (hasPendingApplication) {
                return ApplicationResult.Error("You already have a pending application")
            }
            
            // Check if this Minecraft account already has an approved application
            val isAlreadyWhitelisted = transaction {
                val existingUser = MinecraftUser.findById(profile.id)
                if (existingUser != null) {
                    WhitelistApplication.find {
                        (WhitelistDatabase.WhitelistApplications.minecraftUser eq existingUser.id) and
                        (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                    }.count() > 0
                } else {
                    false
                }
            }
            
            if (isAlreadyWhitelisted) {
                return ApplicationResult.Error("This Minecraft account is already whitelisted")
            }
            
            // Create or update the Minecraft user
            val minecraftUser = transaction {
                val existingUser = MinecraftUser.findById(profile.id)
                if (existingUser != null) {
                    // Update username if needed
                    if (existingUser.currentUsername != profile.name) {
                        val oldUsername = existingUser.currentUsername
                        existingUser.currentUsername = profile.name
                        
                        // Add to username history
                        WhitelistDatabase.MinecraftUserName.new {
                            this.minecraftUser = existingUser
                            this.username = profile.name
                            this.recordedBy = discordUser
                        }
                        
                        logger.info("Updated Minecraft username from $oldUsername to ${profile.name}")
                    }
                    existingUser
                } else {
                    // Create new Minecraft user
                    val newUser = MinecraftUser.new(profile.id) {
                        currentUsername = profile.name
                        currentOwner = discordUser
                    }
                    
                    // Add first username entry
                    WhitelistDatabase.MinecraftUserName.new {
                        this.minecraftUser = newUser
                        this.username = profile.name
                        this.recordedBy = discordUser
                    }
                    
                    newUser
                }
            }
            
            // Create the whitelist application
            val application = transaction {
                WhitelistApplication.new {
                    this.discordUser = discordUser
                    this.minecraftUser = minecraftUser
                    this.status = ApplicationStatus.PENDING
                    this.appliedAt = Instant.now()
                    this.eligibleAt = WhitelistDatabase.calculateEligibleTimestamp(Instant.now())
                    this.isModeratorCreated = false
                    this.processedAt = null
                    this.processedBy = null
                    this.overrideReason = null
                    this.notes = null
                }
            }
            
            // Log the application
            transaction {
                WhitelistDatabase.createAuditLog(
                    actionType = WhitelistDatabase.AuditActionType.WHITELIST_APPLY,
                    entityType = WhitelistDatabase.EntityType.WHITELIST_APPLICATION,
                    entityId = application.id.value.toString(),
                    performedBy = discordUser,
                    details = "Applied for whitelist: ${profile.name} (${profile.id})"
                )
            }
            
            logger.info("Created whitelist application for Minecraft user ${profile.name} (${profile.id}) by Discord user $discordId")
            
            return ApplicationResult.Success(
                applicationId = application.id.value,
                eligibleAt = application.eligibleAt
            )
        } catch (e: Exception) {
            logger.error("Error creating whitelist application", e)
            return ApplicationResult.Error("An error occurred: ${e.message}")
        }
    }
    
    /**
     * Get all pending whitelist applications
     */
    fun getPendingApplications(): List<ApplicationInfo> {
        return transaction {
            WhitelistApplication.find {
                WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.PENDING
            }.map { application ->
                val minecraftUser = application.minecraftUser
                val discordUser = application.discordUser
                
                ApplicationInfo(
                    id = application.id.value,
                    minecraftUuid = minecraftUser.id.value,
                    minecraftUsername = minecraftUser.currentUsername,
                    discordId = discordUser.id.value.toString(),
                    discordUsername = discordUser.currentUsername,
                    appliedAt = application.appliedAt,
                    eligibleAt = application.eligibleAt,
                    status = application.status.name,
                    isEligibleNow = application.eligibleAt.isBefore(Instant.now())
                )
            }
        }
    }
    
    /**
     * Approve a whitelist application
     */
    fun approveApplication(applicationId: Int, moderatorDiscordId: String, notes: String? = null): Boolean {
        try {
            val server = this.server ?: return false
            
            // Get the moderator
            val moderator = transaction {
                DiscordUser.findById(moderatorDiscordId.toLong())
            } ?: return false
            
            // Find and update the application
            val success = transaction {
                val application = WhitelistApplication.findById(applicationId) ?: return@transaction false
                
                // Only approve if it's pending
                if (application.status != ApplicationStatus.PENDING) {
                    return@transaction false
                }
                
                // Update application
                application.status = ApplicationStatus.APPROVED
                application.processedAt = Instant.now()
                application.processedBy = moderator
                if (notes != null) {
                    application.notes = notes
                }
                
                // Create audit log
                WhitelistDatabase.createAuditLog(
                    actionType = WhitelistDatabase.AuditActionType.APPLICATION_APPROVE,
                    entityType = WhitelistDatabase.EntityType.WHITELIST_APPLICATION,
                    entityId = application.id.value.toString(),
                    performedBy = moderator,
                    details = "Approved whitelist application for ${application.minecraftUser.currentUsername}" +
                            (if (notes != null) ". Notes: $notes" else "")
                )
                
                // Get the Minecraft user details for adding to vanilla whitelist
                val minecraftUuid = application.minecraftUser.id.value
                val minecraftUsername = application.minecraftUser.currentUsername
                
                // Add to vanilla whitelist
                try {
                    val profile = com.mojang.authlib.GameProfile(minecraftUuid, minecraftUsername)
                    val entry = net.minecraft.server.WhitelistEntry(profile)
                    server.playerManager.whitelist.add(entry)
                } catch (e: Exception) {
                    logger.error("Error adding to vanilla whitelist: ${e.message}")
                }
                
                logger.info("Approved whitelist application #$applicationId for ${application.minecraftUser.currentUsername} by $moderatorDiscordId")
                true
            }
            
            return success
        } catch (e: Exception) {
            logger.error("Error approving application", e)
            return false
        }
    }
    
    /**
     * Reject a whitelist application
     */
    fun rejectApplication(applicationId: Int, moderatorDiscordId: String, notes: String? = null): Boolean {
        try {
            // Get the moderator
            val moderator = transaction {
                DiscordUser.findById(moderatorDiscordId.toLong())
            } ?: return false
            
            // Find and update the application
            val success = transaction {
                val application = WhitelistApplication.findById(applicationId) ?: return@transaction false
                
                // Only reject if it's pending
                if (application.status != ApplicationStatus.PENDING) {
                    return@transaction false
                }
                
                // Update application
                application.status = ApplicationStatus.REJECTED
                application.processedAt = Instant.now()
                application.processedBy = moderator
                if (notes != null) {
                    application.notes = notes
                }
                
                // Create audit log
                WhitelistDatabase.createAuditLog(
                    actionType = WhitelistDatabase.AuditActionType.APPLICATION_REJECT,
                    entityType = WhitelistDatabase.EntityType.WHITELIST_APPLICATION,
                    entityId = application.id.value.toString(),
                    performedBy = moderator,
                    details = "Rejected whitelist application for ${application.minecraftUser.currentUsername}" +
                            (if (notes != null) ". Notes: $notes" else "")
                )
                
                logger.info("Rejected whitelist application #$applicationId for ${application.minecraftUser.currentUsername} by $moderatorDiscordId")
                true
            }
            
            return success
        } catch (e: Exception) {
            logger.error("Error rejecting application", e)
            return false
        }
    }
    
    /**
     * Check if a Minecraft user is whitelisted
     */
    fun isWhitelisted(uuid: UUID): Boolean {
        return transaction {
            WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq EntityID(uuid, WhitelistDatabase.MinecraftUsers)) and
                (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
            }.count() > 0
        }
    }
    
    /**
     * Find a Minecraft user by username (case insensitive)
     */
    fun findMinecraftUserByName(username: String): MinecraftUserInfo? {
        return transaction {
            val minecraftUser = MinecraftUser.find {
                WhitelistDatabase.MinecraftUsers.currentUsername.lowerCase() eq username.lowercase()
            }.firstOrNull() ?: return@transaction null
            
            // Get the owner Discord user from the currentOwner field
            val discordOwner = minecraftUser.currentOwner
            
            // Find the most recent approved application for timing info
            val application = WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
            }.sortedByDescending { it.appliedAt }.firstOrNull()
            
            // Build the user info with Discord links if available
            MinecraftUserInfo(
                uuid = minecraftUser.id.value,
                username = minecraftUser.currentUsername,
                addedAt = application?.appliedAt ?: minecraftUser.createdAt,
                addedBy = application?.processedBy?.id?.value?.toString() ?: "unknown",
                // Only include Discord info if it's not the unmapped user
                discordUserId = if (discordOwner != null && discordOwner.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                    discordOwner.id.value.toString() else null,
                discordUsername = if (discordOwner != null && discordOwner.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                    discordOwner.currentUsername else null
            )
        }
    }
    
    /**
     * Get the Discord user for a Minecraft account
     */
    fun getDiscordUserForMinecraftAccount(minecraftUuid: UUID): DiscordUserInfo? {
        return transaction {
            // Find the Minecraft user
            val minecraftUser = MinecraftUser.findById(minecraftUuid) ?: return@transaction null
            
            // Get the current owner
            val discordUser = minecraftUser.currentOwner ?: return@transaction null
            
            // Skip if it's the unmapped user
            if (discordUser.id.value == WhitelistDatabase.UNMAPPED_DISCORD_ID) {
                return@transaction null
            }
            
            DiscordUserInfo(
                id = discordUser.id.value.toString(),
                username = discordUser.currentUsername
            )
        }
    }
    
    /**
     * Get all Minecraft accounts for a Discord user
     */
    fun getMinecraftAccountsForDiscordUser(discordUserId: String): List<MinecraftUserInfo> {
        return transaction {
            // Find the Discord user
            val discordUser = DiscordUser.findById(discordUserId.toLong()) ?: return@transaction emptyList()
            
            // Find all Minecraft users owned by this Discord user
            val minecraftUsers = MinecraftUser.find {
                WhitelistDatabase.MinecraftUsers.currentOwner eq discordUser.id
            }
            
            minecraftUsers.map { minecraftUser ->
                // Find the most recent approved application for this user
                val application = WhitelistApplication.find {
                    (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                    (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                }.sortedByDescending { it.appliedAt }.firstOrNull()
                
                MinecraftUserInfo(
                    uuid = minecraftUser.id.value,
                    username = minecraftUser.currentUsername,
                    addedAt = application?.appliedAt ?: minecraftUser.createdAt,
                    addedBy = application?.processedBy?.id?.value?.toString() ?: "unknown"
                )
            }
        }
    }
    
    /**
     * Get whitelist history for a specific player
     */
    fun getWhitelistHistory(minecraftUuid: UUID, limit: Int = 10): List<WhitelistEventInfo> {
        return transaction {
            // Find all whitelist applications for this user
            WhitelistApplication.find {
                WhitelistDatabase.WhitelistApplications.minecraftUser eq EntityID(minecraftUuid, WhitelistDatabase.MinecraftUsers)
            }.sortedByDescending { it.appliedAt }.take(limit).map { application ->
                val minecraftUser = application.minecraftUser
                val discordUser = application.discordUser
                val processedBy = application.processedBy
                
                // Convert application status to event type
                val eventType = when {
                    application.status == ApplicationStatus.APPROVED && application.isModeratorCreated -> "ADD"
                    application.status == ApplicationStatus.APPROVED -> "APPROVE"
                    application.status == ApplicationStatus.REJECTED -> "REJECT"
                    application.status == ApplicationStatus.REMOVED -> "REMOVE"
                    application.status == ApplicationStatus.PENDING -> "APPLY"
                    else -> "UNKNOWN"
                }
                
                WhitelistEventInfo(
                    minecraftUserId = minecraftUser.id.value,
                    minecraftUsername = minecraftUser.currentUsername,
                    discordUserId = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                        discordUser.id.value.toString() else null,
                    discordUsername = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                        discordUser.currentUsername else null,
                    eventType = eventType,
                    timestamp = application.appliedAt,
                    actorDiscordId = processedBy?.id?.value?.toString(),
                    actorDiscordUsername = processedBy?.currentUsername,
                    comment = application.notes,
                    details = if (application.overrideReason != null) "Override: ${application.overrideReason}" else null
                )
            }
        }
    }
    
    /**
     * Get recent whitelist events across all players
     */
    fun getRecentWhitelistHistory(limit: Int = 10): List<WhitelistEventInfo> {
        return transaction {
            // Get all applications, sorted by date
            WhitelistApplication.all()
                .sortedByDescending { it.appliedAt }
                .take(limit)
                .map { application ->
                    val minecraftUser = application.minecraftUser
                    val discordUser = application.discordUser
                    val processedBy = application.processedBy
                    
                    // Convert application status to event type
                    val eventType = when {
                        application.status == ApplicationStatus.APPROVED && application.isModeratorCreated -> "ADD"
                        application.status == ApplicationStatus.APPROVED -> "APPROVE"
                        application.status == ApplicationStatus.REJECTED -> "REJECT"
                        application.status == ApplicationStatus.REMOVED -> "REMOVE"
                        application.status == ApplicationStatus.PENDING -> "APPLY"
                        else -> "UNKNOWN"
                    }
                    
                    WhitelistEventInfo(
                        minecraftUserId = minecraftUser.id.value,
                        minecraftUsername = minecraftUser.currentUsername,
                        discordUserId = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                            discordUser.id.value.toString() else null,
                        discordUsername = if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) 
                            discordUser.currentUsername else null,
                        eventType = eventType,
                        timestamp = application.appliedAt,
                        actorDiscordId = processedBy?.id?.value?.toString(),
                        actorDiscordUsername = processedBy?.currentUsername,
                        comment = application.notes,
                        details = if (application.overrideReason != null) "Override: ${application.overrideReason}" else null
                    )
                }
        }
    }
    
    /**
     * Get whitelist history for a player by Minecraft username
     */
    fun getWhitelistHistoryByMinecraftName(minecraftName: String, limit: Int = 10): List<WhitelistEventInfo> {
        // Find the Minecraft user by username
        val minecraftUser = transaction {
            MinecraftUser.find {
                WhitelistDatabase.MinecraftUsers.currentUsername.lowerCase() eq minecraftName.lowercase()
            }.firstOrNull()
        } ?: return emptyList()
        
        // Get history using UUID
        return getWhitelistHistory(minecraftUser.id.value, limit)
    }
    
    /**
     * Get whitelist history for all Minecraft accounts linked to a Discord user
     */
    fun getWhitelistHistoryByDiscordId(discordUserId: String, limit: Int = 10): List<WhitelistEventInfo> {
        return transaction {
            // Find the Discord user
            val discordUser = DiscordUser.findById(discordUserId.toLong()) ?: return@transaction emptyList()
            
            // Get all applications for this Discord user
            WhitelistApplication.find {
                WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id
            }.sortedByDescending { it.appliedAt }.take(limit).map { application ->
                val minecraftUser = application.minecraftUser
                val processedBy = application.processedBy
                
                // Convert application status to event type
                val eventType = when {
                    application.status == ApplicationStatus.APPROVED && application.isModeratorCreated -> "ADD"
                    application.status == ApplicationStatus.APPROVED -> "APPROVE"
                    application.status == ApplicationStatus.REJECTED -> "REJECT"
                    application.status == ApplicationStatus.REMOVED -> "REMOVE"
                    application.status == ApplicationStatus.PENDING -> "APPLY"
                    else -> "UNKNOWN"
                }
                
                WhitelistEventInfo(
                    minecraftUserId = minecraftUser.id.value,
                    minecraftUsername = minecraftUser.currentUsername,
                    discordUserId = discordUser.id.value.toString(),
                    discordUsername = discordUser.currentUsername,
                    eventType = eventType,
                    timestamp = application.appliedAt,
                    actorDiscordId = processedBy?.id?.value?.toString(),
                    actorDiscordUsername = processedBy?.currentUsername,
                    comment = application.notes,
                    details = if (application.overrideReason != null) "Override: ${application.overrideReason}" else null
                )
            }
        }
    }
    
    /**
     * Check if a Minecraft username is already on the whitelist
     */
    fun isUsernameWhitelisted(username: String): Boolean {
        return transaction {
            val minecraftUser = MinecraftUser.find {
                WhitelistDatabase.MinecraftUsers.currentUsername.lowerCase() eq username.lowercase()
            }.firstOrNull() ?: return@transaction false
            
            WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
            }.count() > 0
        }
    }
    
    /**
     * Search users based on filters
     */
    fun searchUsers(filters: UserSearchFilters): List<UserSearchResult> {
        return transaction {
            // Start with all possible Minecraft users
            var minecraftUserQuery = MinecraftUser.all()
            
            // Apply Minecraft username filter (case-insensitive substring match)
            if (filters.minecraftUsername != null) {
                minecraftUserQuery = MinecraftUser.find {
                    WhitelistDatabase.MinecraftUsers.currentUsername.lowerCase() like 
                        "%${filters.minecraftUsername.lowercase()}%"
                }
            }
            
            // Convert to a list we can filter in memory
            val minecraftUsers = minecraftUserQuery.toList()
            
            // Apply Discord username filter - this is more complex as we need to join
            val filteredUsers = filters.discordUsername?.let { discordName ->
                minecraftUsers.filter { minecraftUser ->
                    minecraftUser.currentOwner?.let { owner ->
                        owner.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID &&
                        owner.currentUsername.lowercase().contains(discordName.lowercase())
                    } ?: false
                }
            } ?: minecraftUsers
            
            // Apply Discord ID filter
            val afterDiscordIdFilter = filters.discordId?.toLongOrNull()?.let { discordIdLong ->
                filteredUsers.filter { minecraftUser ->
                    minecraftUser.currentOwner?.id?.value == discordIdLong
                }
            } ?: filteredUsers
            
            // Apply has Discord link filter
            val afterDiscordLinkFilter = filters.hasDiscordLink?.let { hasDiscordFilter ->
                afterDiscordIdFilter.filter { minecraftUser ->
                    val hasLink = minecraftUser.currentOwner != null && 
                                 minecraftUser.currentOwner?.id?.value != WhitelistDatabase.UNMAPPED_DISCORD_ID
                    hasDiscordFilter == hasLink
                }
            } ?: afterDiscordIdFilter
            
            // Get approval status for each user
            val results = afterDiscordLinkFilter.map { minecraftUser ->
                // Find the most recent approved application for timing info
                val application = WhitelistApplication.find {
                    (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id) and
                    (WhitelistDatabase.WhitelistApplications.status eq ApplicationStatus.APPROVED)
                }.sortedByDescending { it.appliedAt }.firstOrNull()
                
                val isWhitelisted = application != null
                
                // Apply whitelist status filter
                if (filters.isWhitelisted != null && filters.isWhitelisted != isWhitelisted) {
                    return@map null
                }
                
                // Apply added by filter
                if (filters.addedBy != null && application != null) {
                    val processedBy = application.processedBy
                    if (processedBy == null || processedBy.id.value.toString() != filters.addedBy) {
                        return@map null
                    }
                }
                
                // Apply time range filters
                if (application != null) {
                    if (filters.addedBefore != null && application.appliedAt.isAfter(filters.addedBefore)) {
                        return@map null
                    }
                    if (filters.addedAfter != null && application.appliedAt.isBefore(filters.addedAfter)) {
                        return@map null
                    }
                }
                
                val discordInfo = minecraftUser.currentOwner?.let { discordUser ->
                    if (discordUser.id.value != WhitelistDatabase.UNMAPPED_DISCORD_ID) {
                        DiscordUserInfo(
                            id = discordUser.id.value.toString(),
                            username = discordUser.currentUsername
                        )
                    } else null
                }
                
                val minecraftInfo = MinecraftUserInfo(
                    uuid = minecraftUser.id.value,
                    username = minecraftUser.currentUsername,
                    addedAt = application?.appliedAt ?: minecraftUser.createdAt,
                    addedBy = application?.processedBy?.id?.value?.toString() ?: "unknown",
                    discordUserId = discordInfo?.id,
                    discordUsername = discordInfo?.username
                )
                
                UserSearchResult(
                    minecraftInfo = minecraftInfo,
                    discordInfo = discordInfo,
                    isWhitelisted = isWhitelisted,
                    addedAt = application?.appliedAt,
                    addedBy = application?.processedBy?.id?.value?.toString()
                )
            }
            
            // Filter out nulls and limit results
            results.filterNotNull().take(filters.limit)
        }
    }
    
    /**
     * Get a game profile from the Minecraft server
     */
    private fun getGameProfile(username: String): GameProfile? {
        val server = this.server ?: return null
        val userCache = server.getUserCache()
        
        // Try to find the player's profile in the cache first
        val profileOptional = userCache?.findByName(username)
        if (profileOptional != null && profileOptional.isPresent) {
            return profileOptional.get()
        }
        
        // If not in cache, try to get it asynchronously but with a timeout
        try {
            val future = userCache?.findByNameAsync(username)
            if (future != null) {
                val timeout = ArgusConfig.get().timeouts.profileLookupSeconds
                val result = future.get(timeout, TimeUnit.SECONDS)
                if (result.isPresent) {
                    return result.get()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get game profile for $username: ${e.message}")
        }
        
        // If all else fails, try to create an offline profile
        // Note: This will only work in offline mode servers
        if (!server.isOnlineMode) {
            logger.info("Creating offline profile for $username")
            return GameProfile(
                Uuids.getOfflinePlayerUuid(username),
                username
            )
        }
        
        logger.warn("Could not resolve profile for $username")
        return null
    }
}

/**
 * Result of a whitelist application submission
 */
sealed class ApplicationResult {
    data class Success(val applicationId: Int, val eligibleAt: Instant) : ApplicationResult()
    data class Error(val message: String) : ApplicationResult()
}

/**
 * Information about a whitelist application
 */
data class ApplicationInfo(
    val id: Int,
    val minecraftUuid: UUID,
    val minecraftUsername: String,
    val discordId: String,
    val discordUsername: String,
    val appliedAt: Instant,
    val eligibleAt: Instant,
    val status: String,
    val isEligibleNow: Boolean
)

/**
 * Minecraft user information
 */
data class MinecraftUserInfo(
    val uuid: UUID,
    val username: String,
    val addedAt: Instant,
    val addedBy: String,
    val discordUserId: String? = null,
    val discordUsername: String? = null
)

/**
 * Discord user information
 */
data class DiscordUserInfo(
    val id: String,
    val username: String
)

/**
 * Search filters for user lookup
 */
data class UserSearchFilters(
    val minecraftUsername: String? = null,         // Substring match for Minecraft username
    val discordUsername: String? = null,           // Substring match for Discord username
    val discordId: String? = null,                 // Exact match for Discord ID
    val hasDiscordLink: Boolean? = null,           // Whether the account has a Discord link
    val isWhitelisted: Boolean? = null,            // Whether the account is whitelisted
    val addedBefore: Instant? = null,              // Added before this time
    val addedAfter: Instant? = null,               // Added after this time
    val addedBy: String? = null,                   // Added by this Discord ID
    val limit: Int = 20                            // Maximum number of results to return
)

/**
 * Combined user information for search results
 */
data class UserSearchResult(
    val minecraftInfo: MinecraftUserInfo? = null,
    val discordInfo: DiscordUserInfo? = null,
    val isWhitelisted: Boolean = false,
    val addedAt: Instant? = null,
    val addedBy: String? = null
)

/**
 * Whitelist event information
 */
data class WhitelistEventInfo(
    val minecraftUserId: UUID,
    val minecraftUsername: String,
    val discordUserId: String?,
    val discordUsername: String?,
    val eventType: String,
    val timestamp: Instant,
    val actorDiscordId: String?,
    val actorDiscordUsername: String?,
    val comment: String?,
    val details: String?
)