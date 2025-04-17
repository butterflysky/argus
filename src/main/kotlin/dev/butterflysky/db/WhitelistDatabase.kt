package dev.butterflysky.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.slf4j.LoggerFactory
import dev.butterflysky.config.ArgusConfig
import java.time.Instant
import java.util.UUID
import java.io.File

/**
 * WhitelistDatabase contains the schema definition for the Abcraft whitelist management system.
 * 
 * Core design principles:
 * 1. Discord accounts are the primary identifier for players (using immutable Discord IDs)
 * 2. Minecraft accounts can be transferred between players
 * 3. All username changes are tracked for both platforms
 * 4. Moderator actions are audited
 * 5. Whitelist eligibility follows a 48-hour cooldown by default
 */
object WhitelistDatabase {
    private val logger = LoggerFactory.getLogger("argus-db")
    private var initialized = false
    private var dbFile: File? = null

    /**
     * Special constant for the system-generated Discord ID that represents
     * legacy Minecraft accounts imported from whitelist.json without Discord mapping
     */
    const val UNMAPPED_DISCORD_ID: Long = -1L
    
    /**
     * Special constant for the system user that represents actions taken by the bot
     * rather than a human moderator. This ensures we have a consistent actor for
     * audit logging of automated actions.
     */
    const val SYSTEM_USER_ID: Long = -2L
    
    // Tables
    object DiscordUsers : LongIdTable("discord_users") {
        // Using Discord's numeric ID as the primary key since it's immutable
        val currentUsername = varchar("current_username", 128)
        val currentServername = varchar("current_servername", 128).nullable()
        val joinedServerAt = timestamp("joined_server_at")
        // Track if user has left the Discord server
        val isInServer = bool("is_in_server").default(true)
        val leftServerAt = timestamp("left_server_at").nullable()
        val createdAt = timestamp("created_at").default(Instant.now())
    }

    object DiscordUserNameHistory : IntIdTable("discord_username_history") {
        val discordUser = reference("discord_id", DiscordUsers, onDelete = ReferenceOption.CASCADE)
        val username = varchar("username", 128)
        val type = enumeration("type", NameType::class)
        val recordedAt = timestamp("recorded_at").default(Instant.now())
        val recordedBy = reference("recorded_by", DiscordUsers, onDelete = ReferenceOption.SET_NULL).nullable()
    }

    object MinecraftUsers : UUIDTable("minecraft_users") {
        val currentUsername = varchar("current_username", 128)
        val createdAt = timestamp("created_at").default(Instant.now())
        val currentOwner = reference("current_owner_id", DiscordUsers, onDelete = ReferenceOption.SET_NULL).nullable()
        val transferredAt = timestamp("transferred_at").nullable()
    }

    object MinecraftUsernameHistory : IntIdTable("minecraft_username_history") {
        val minecraftUser = reference("minecraft_uuid", MinecraftUsers, onDelete = ReferenceOption.CASCADE)
        val username = varchar("username", 128)
        val recordedAt = timestamp("recorded_at").default(Instant.now())
        val recordedBy = reference("recorded_by", DiscordUsers, onDelete = ReferenceOption.SET_NULL).nullable()
    }

    /**
     * WhitelistApplications tracks the relationship between Discord users and Minecraft accounts
     * for the purpose of whitelisting. This includes both user-initiated applications and 
     * moderator-created whitelist entries.
     * 
     * The 48-hour cooldown is enforced through the eligibleAt timestamp, but moderators
     * can override this restriction with a provided reason.
     */
    object WhitelistApplications : IntIdTable("whitelist_applications") {
        val discordUser = reference("discord_id", DiscordUsers, onDelete = ReferenceOption.CASCADE)
        val minecraftUser = reference("minecraft_uuid", MinecraftUsers, onDelete = ReferenceOption.CASCADE)
        val status = enumeration("status", ApplicationStatus::class)
        val appliedAt = timestamp("applied_at").default(Instant.now())
        val eligibleAt = timestamp("eligible_at")
        // Track if this was a direct moderator whitelist (no application required)
        val isModeratorCreated = bool("is_moderator_created").default(false)
        val processedAt = timestamp("processed_at").nullable()
        val processedBy = reference("processed_by", DiscordUsers, onDelete = ReferenceOption.SET_NULL).nullable()
        val overrideReason = text("override_reason").nullable()
        val notes = text("notes").nullable()
    }

    object AuditLogs : IntIdTable("audit_logs") {
        val actionType = varchar("action_type", 32)
        val entityType = varchar("entity_type", 32)
        val entityId = varchar("entity_id", 64)
        val performedBy = reference("performed_by", DiscordUsers, onDelete = ReferenceOption.SET_NULL).nullable()
        val performedAt = timestamp("performed_at").default(Instant.now())
        val details = text("details")
    }

    // Entities
    class DiscordUser(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<DiscordUser>(DiscordUsers) {
            /**
             * Creates or retrieves the special unmapped Discord user placeholder
             * for Minecraft accounts imported from whitelist.json without a Discord mapping
             */
            fun getUnmappedUser(): DiscordUser = transaction {
                findById(UNMAPPED_DISCORD_ID) ?: new(UNMAPPED_DISCORD_ID) {
                    currentUsername = "Unmapped Minecraft User"
                    currentServername = "Unmapped"
                    joinedServerAt = Instant.EPOCH
                    isInServer = false
                }
            }
            
            /**
             * Creates or retrieves the system user that represents actions taken by the bot
             * rather than a human moderator
             */
            fun getSystemUser(): DiscordUser = transaction {
                findById(SYSTEM_USER_ID) ?: new(SYSTEM_USER_ID) {
                    currentUsername = "System"
                    currentServername = "System"
                    joinedServerAt = Instant.EPOCH
                    isInServer = true
                }
            }
        }

        var currentUsername by DiscordUsers.currentUsername
        var currentServername by DiscordUsers.currentServername
        var joinedServerAt by DiscordUsers.joinedServerAt
        var isInServer by DiscordUsers.isInServer
        var leftServerAt by DiscordUsers.leftServerAt
        var createdAt by DiscordUsers.createdAt
        
        val nameHistory by DiscordUserName referrersOn DiscordUserNameHistory.discordUser
        val minecraftUsers by MinecraftUser optionalReferrersOn MinecraftUsers.currentOwner
        val applications by WhitelistApplication referrersOn WhitelistApplications.discordUser
        
        /**
         * Records that a user has left the Discord server.
         * We keep their records but mark them as no longer in the server.
         */
        fun markAsLeft() = apply {
            isInServer = false
            leftServerAt = Instant.now()
        }
        
        /**
         * Records that a user has rejoined the Discord server.
         */
        fun markAsRejoined() = apply {
            isInServer = true
            leftServerAt = null
        }
    }

    class DiscordUserName(id: EntityID<Int>) : IntEntity(id) {
        companion object : IntEntityClass<DiscordUserName>(DiscordUserNameHistory)

        var discordUser by DiscordUser referencedOn DiscordUserNameHistory.discordUser
        var username by DiscordUserNameHistory.username
        var type by DiscordUserNameHistory.type
        var recordedAt by DiscordUserNameHistory.recordedAt
        var recordedBy by DiscordUser optionalReferencedOn DiscordUserNameHistory.recordedBy
    }

    class MinecraftUser(id: EntityID<UUID>) : UUIDEntity(id) {
        companion object : UUIDEntityClass<MinecraftUser>(MinecraftUsers)

        var currentUsername by MinecraftUsers.currentUsername
        var createdAt by MinecraftUsers.createdAt
        var currentOwner by DiscordUser optionalReferencedOn MinecraftUsers.currentOwner
        var transferredAt by MinecraftUsers.transferredAt
        
        val usernameHistory by MinecraftUserName referrersOn MinecraftUsernameHistory.minecraftUser
        val applications by WhitelistApplication referrersOn WhitelistApplications.minecraftUser
    }

    class MinecraftUserName(id: EntityID<Int>) : IntEntity(id) {
        companion object : IntEntityClass<MinecraftUserName>(MinecraftUsernameHistory)

        var minecraftUser by MinecraftUser referencedOn MinecraftUsernameHistory.minecraftUser
        var username by MinecraftUsernameHistory.username
        var recordedAt by MinecraftUsernameHistory.recordedAt
        var recordedBy by DiscordUser optionalReferencedOn MinecraftUsernameHistory.recordedBy
    }

    class WhitelistApplication(id: EntityID<Int>) : IntEntity(id) {
        companion object : IntEntityClass<WhitelistApplication>(WhitelistApplications) {
            /**
             * Create a moderator-initiated whitelist entry without requiring a user application.
             * Moderators can directly whitelist a Minecraft account for a Discord user.
             */
            fun createModeratorWhitelist(
                discordUser: DiscordUser,
                minecraftUser: MinecraftUser,
                moderator: DiscordUser,
                overrideReason: String,
                notes: String? = null
            ): WhitelistApplication = new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                this.status = ApplicationStatus.APPROVED
                this.appliedAt = Instant.now()
                this.eligibleAt = Instant.now() // Immediately eligible
                this.isModeratorCreated = true
                this.processedAt = Instant.now()
                this.processedBy = moderator
                this.overrideReason = overrideReason
                this.notes = notes
            }
            
            /**
             * Create a whitelist entry for legacy Minecraft accounts imported from whitelist.json
             * that don't have an associated Discord account yet.
             */
            fun createLegacyWhitelist(
                minecraftUser: MinecraftUser,
                moderator: DiscordUser,
                notes: String? = null
            ): WhitelistApplication = new {
                this.discordUser = DiscordUser.getUnmappedUser()
                this.minecraftUser = minecraftUser
                this.status = ApplicationStatus.APPROVED
                this.appliedAt = Instant.EPOCH  // Set to a very old date to indicate legacy
                this.eligibleAt = Instant.EPOCH
                this.isModeratorCreated = true
                this.processedAt = Instant.now()
                this.processedBy = moderator
                this.overrideReason = "Legacy whitelist import"
                this.notes = notes
            }
        }

        var discordUser by DiscordUser referencedOn WhitelistApplications.discordUser
        var minecraftUser by MinecraftUser referencedOn WhitelistApplications.minecraftUser
        var status by WhitelistApplications.status
        var appliedAt by WhitelistApplications.appliedAt
        var eligibleAt by WhitelistApplications.eligibleAt
        var isModeratorCreated by WhitelistApplications.isModeratorCreated
        var processedAt by WhitelistApplications.processedAt
        var processedBy by DiscordUser optionalReferencedOn WhitelistApplications.processedBy
        var overrideReason by WhitelistApplications.overrideReason
        var notes by WhitelistApplications.notes
    }

    class AuditLog(id: EntityID<Int>) : IntEntity(id) {
        companion object : IntEntityClass<AuditLog>(AuditLogs)

        var actionType by AuditLogs.actionType
        var entityType by AuditLogs.entityType
        var entityId by AuditLogs.entityId
        var performedBy by DiscordUser optionalReferencedOn AuditLogs.performedBy
        var performedAt by AuditLogs.performedAt
        var details by AuditLogs.details
    }

    // Enums
    enum class NameType {
        USERNAME, SERVERNAME, NICKNAME
    }

    enum class ApplicationStatus {
        PENDING, APPROVED, REJECTED, REMOVED
    }
    
    /**
     * Enumeration of all possible audit log action types
     */
    enum class AuditActionType(val displayName: String) {
        WHITELIST_ADD("Add to Whitelist"),
        WHITELIST_REMOVE("Remove from Whitelist"),
        WHITELIST_APPLY("Whitelist Application"),
        APPLICATION_APPROVE("Application Approved"),
        APPLICATION_REJECT("Application Rejected"),
        ACCOUNT_LINK("Account Link"),
        USER_LEFT("User Left"),
        LEGACY_IMPORT("Legacy Import"),
        ACCOUNT_TRANSFER("Account Transfer")
    }
    
    /**
     * Enumeration of entity types for audit logs
     */
    enum class EntityType(val displayName: String) {
        DISCORD_USER("Discord User"),
        MINECRAFT_USER("Minecraft User"),
        WHITELIST_APPLICATION("Whitelist Application")
    }
    
    // Helper functions
    
    /**
     * Creates a detailed audit log entry for tracking all changes in the system.
     * This is crucial for security and accountability, as all actions are tracked.
     * 
     * @param actionType The type of action performed
     * @param entityType The type of entity affected
     * @param entityId The ID of the affected entity
     * @param performedBy The user who performed the action, or null for system actions
     * @param details Additional details about the action
     * @return The created AuditLog entity
     */
    fun createAuditLog(
        actionType: AuditActionType,
        entityType: EntityType,
        entityId: String,
        performedBy: DiscordUser? = null,
        details: String
    ): AuditLog {
        // If performedBy is null, use the system user
        val actor = performedBy ?: DiscordUser.getSystemUser()
        
        return AuditLog.new {
            this.actionType = actionType.name
            this.entityType = entityType.name
            this.entityId = entityId
            this.performedBy = actor
            this.details = details
        }
    }

    /**
     * Calculates when a player becomes eligible for whitelisting.
     * By default, there's a 48-hour cooldown period to prevent griefing.
     * Moderators can override this with appropriate reasoning.
     */
    fun calculateEligibleTimestamp(appliedAt: Instant): Instant {
        val cooldownHours = ArgusConfig.get().whitelist.cooldownHours
        return appliedAt.plusSeconds(cooldownHours * 60 * 60) // Convert hours to seconds
    }
    
    /**
     * Handles the case when a user leaves the Discord server.
     * We keep their records but mark them as no longer in the server,
     * which allows us to unwhitelist them while preserving history.
     */
    fun handleUserLeft(discordId: Long, performedBy: DiscordUser?): DiscordUser? {
        return transaction {
            DiscordUser.findById(discordId)?.apply {
                markAsLeft()
                createAuditLog(
                    actionType = AuditActionType.USER_LEFT,
                    entityType = EntityType.DISCORD_USER,
                    entityId = id.value.toString(),
                    performedBy = performedBy,
                    details = "User left the Discord server"
                )
            }
        }
    }
    
    /**
     * Handles Minecraft account ownership transfers.
     * Minecraft accounts can be transferred between players, but this requires
     * unwhitelisting until a moderator reviews the transfer.
     */
    fun transferMinecraftUser(
        minecraftUuid: UUID,
        newOwnerId: Long,
        performedBy: DiscordUser,
        reason: String
    ): MinecraftUser? {
        return transaction {
            val account = MinecraftUser.findById(minecraftUuid) ?: return@transaction null
            val newOwner = DiscordUser.findById(newOwnerId) ?: return@transaction null
            
            // Get previous owner for the audit log
            val previousOwner = account.currentOwner
            
            // Update the account ownership
            account.currentOwner = newOwner
            account.transferredAt = Instant.now()
            
            // Find any active whitelist applications and mark them as removed
            WhitelistApplication.find {
                (WhitelistApplications.minecraftUser eq account.id) and
                (WhitelistApplications.status eq ApplicationStatus.APPROVED)
            }.forEach { 
                it.status = ApplicationStatus.REMOVED
                
                createAuditLog(
                    actionType = AuditActionType.WHITELIST_REMOVE,
                    entityType = EntityType.WHITELIST_APPLICATION,
                    entityId = it.id.value.toString(),
                    performedBy = performedBy,
                    details = "Automatically removed due to account transfer: $reason"
                )
            }
            
            // Create audit log for the transfer
            createAuditLog(
                actionType = AuditActionType.ACCOUNT_TRANSFER,
                entityType = EntityType.MINECRAFT_USER,
                entityId = account.id.value.toString(),
                performedBy = performedBy,
                details = "Account transferred from ${previousOwner?.currentUsername ?: "Unknown"} " +
                          "to ${newOwner.currentUsername}. Reason: $reason"
            )
            
            account
        }
    }
    
    /**
     * Imports legacy Minecraft accounts from whitelist.json that don't have
     * associated Discord accounts yet. These are temporarily mapped to the
     * special UNMAPPED_DISCORD_ID until a proper Discord mapping is established.
     */
    fun importLegacyMinecraftUser(
        minecraftUuid: UUID,
        username: String,
        performedBy: DiscordUser
    ): Pair<MinecraftUser, WhitelistApplication>? {
        return transaction {
            // Check if account already exists
            val existingAccount = MinecraftUser.findById(minecraftUuid)
            if (existingAccount != null) {
                return@transaction null
            }
            
            // Create new Minecraft account
            val account = MinecraftUser.new(minecraftUuid) {
                currentUsername = username
                currentOwner = DiscordUser.getUnmappedUser()
                transferredAt = null
            }
            
            // Track the username
            MinecraftUserName.new {
                minecraftUser = account
                this.username = username
                recordedBy = performedBy
            }
            
            // Create legacy whitelist entry
            val application = WhitelistApplication.createLegacyWhitelist(
                minecraftUser = account,
                moderator = performedBy,
                notes = "Imported from whitelist.json"
            )
            
            // Create audit log
            createAuditLog(
                actionType = AuditActionType.LEGACY_IMPORT,
                entityType = EntityType.MINECRAFT_USER,
                entityId = account.id.value.toString(),
                performedBy = performedBy,
                details = "Imported legacy Minecraft account $username from whitelist.json"
            )
            
            Pair(account, application)
        }
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
                    DiscordUsers,
                    DiscordUserNameHistory,
                    MinecraftUsers,
                    MinecraftUsernameHistory,
                    WhitelistApplications,
                    AuditLogs
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
    fun isInitialized() = initialized
}