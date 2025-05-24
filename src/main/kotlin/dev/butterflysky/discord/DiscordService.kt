package dev.butterflysky.discord

import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.service.WhitelistService
import dev.butterflysky.db.WhitelistDatabase
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import dev.butterflysky.util.ThreadPools
import dev.butterflysky.util.RateLimiter
import java.util.function.Consumer
import net.minecraft.server.MinecraftServer
import java.util.UUID
import dev.butterflysky.discord.GlobalBanCommand
import dev.butterflysky.discord.WhitelistCommands

/**
 * Service for managing Discord bot interactions
 */
class DiscordService : ListenerAdapter() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-discord")
        
        // Singleton instance
        private var instance: DiscordService? = null
        
        fun getInstance(): DiscordService {
            if (instance == null) {
                instance = DiscordService()
            }
            return instance!!
        }
    }
    
    private var jda: JDA? = null
    private var guild: Guild? = null
    private val scheduler: ScheduledExecutorService = ThreadPools.discordReconnectExecutor
    private var reconnectTask: ScheduledFuture<*>? = null
    private var currentReconnectDelay: Long = 0
    private var adminRoles: List<String> = listOf()
    private var patronRole: String = ""
    private var adultRole: String = ""
    private var loggingChannel: String = ""
    private val commandRateLimiter = RateLimiter(20, 10000) // 20 commands per 10 seconds
    
    private var minecraftServer: MinecraftServer? = null
    private val whitelistService = WhitelistService.getInstance()
    private val globalBanCommand = GlobalBanCommand(whitelistService)
    private var whitelistCommandsInstance: WhitelistCommands? = null
    
    private val commandHandlers = mutableMapOf<String, CommandHandler>()
    
    /**
     * Initialize the Discord service
     */
    fun init() {
        val config = ArgusConfig.get()
        if (!config.discord.enabled) {
            logger.info("Discord integration is disabled in config")
            return
        }
        
        adminRoles = config.discord.adminRoles
        patronRole = config.discord.patronRole
        adultRole = config.discord.adultRole
        loggingChannel = config.discord.loggingChannel
        connect()
    }
    
    /**
     * Check if Discord service is connected
     */
    fun isConnected() = jda?.status == JDA.Status.CONNECTED
    
    /**
     * Set the Minecraft server instance
     */
    fun setMinecraftServer(server: MinecraftServer) {
        this.minecraftServer = server
        
        // Initialize whitelist service with server
        try {
            whitelistService.initialize(server)
            // Instantiate WhitelistCommands and register its handlers now that server is available
            if (this.minecraftServer != null) {
                this.whitelistCommandsInstance = WhitelistCommands(this.minecraftServer!!)
                this.whitelistCommandsInstance?.registerHandlers()
                logger.info("Whitelist command handlers registered after Minecraft server set.")
            } else {
                logger.error("MinecraftServer instance is null in setMinecraftServer, cannot register WhitelistCommands handlers.")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize whitelist service or register handlers", e)
        }
    }
    
    /**
     * Attempt to connect to Discord
     */
    private fun connect() {
        val config = ArgusConfig.get()
        
        try {
            logger.info("Connecting to Discord...")
            
            // Create the JDA builder but don't await ready yet
            val jdaBuilder = JDABuilder.createDefault(config.discord.token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
            
            // Build the JDA instance and add a ready listener to handle guild setup
            jda = jdaBuilder.build()
            
            // Add a ready listener to handle guild setup and command registration
            jda?.addEventListener(object : net.dv8tion.jda.api.hooks.EventListener {
                override fun onEvent(event: net.dv8tion.jda.api.events.GenericEvent) {
                    if (event is net.dv8tion.jda.api.events.session.ReadyEvent) {
                        logger.info("JDA ready event received - bot is connected to Discord")
                        
                        // Get the guild from config
                        val guildId = config.discord.guildId
                        
                        // Check available guilds and find our target guild
                        logger.info("JDA ready event received - checking available guilds")
                        
                        // Don't use awaitReady() as it can block indefinitely
                        val availableGuilds = jda?.guilds ?: emptyList()
                        
                        logger.info("Connected to ${availableGuilds.size} guilds")
                        val guildNames = availableGuilds.map { "${it.name} (${it.id})" }
                        logger.info("Available guilds: $guildNames")
                        
                        // Try to find our guild by ID
                        val targetGuild = availableGuilds.find { it.id == guildId }
                        
                        if (targetGuild != null) {
                            guild = targetGuild
                            logger.info("Found configured guild: ${guild?.name} (${guild?.id})")
                            
                            logger.info("Connected to Discord guild: ${guild?.name}")
                            
                            // Register slash commands after we've found our guild
                            registerCommands()
                            
                            // Reset reconnect delay on successful connection
                            currentReconnectDelay = 0
                        } else {
                            if (availableGuilds.isEmpty()) {
                                logger.error("No guilds available at all")
                            } else {
                                logger.error("Guild ID $guildId not found. Available: $guildNames")
                            }
                            
                            // Schedule a reconnect if we couldn't find our guild
                            logger.info("Scheduling reconnect to retry finding guild $guildId")
                            scheduleReconnect()
                        }
                        
                        // Remove this listener as we don't need it anymore
                        jda?.removeEventListener(this)
                    }
                }
            })
            
            // Wait for JDA to be ready, but don't block guild setup
            logger.info("JDA instance created, waiting for ready state...")
            
        } catch (e: Exception) {
            logger.error("Failed to connect to Discord", e)
            scheduleReconnect()
        }
    }
    
    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private fun scheduleReconnect() {
        val config = ArgusConfig.get()
        
        if (currentReconnectDelay == 0L) {
            currentReconnectDelay = config.reconnect.initialDelayMs
        } else {
            currentReconnectDelay = (currentReconnectDelay * config.reconnect.backoffMultiplier).toLong()
            if (currentReconnectDelay > config.reconnect.maxDelayMs) {
                currentReconnectDelay = config.reconnect.maxDelayMs
            }
        }
        
        logger.info("Scheduling reconnection attempt in ${currentReconnectDelay}ms")
        
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({
            if (jda?.status == JDA.Status.CONNECTED) {
                logger.info("Already connected, cancelling reconnect")
                return@schedule
            }
            
            try {
                jda?.shutdownNow()
            } catch (e: Exception) {
                logger.error("Error shutting down previous JDA instance", e)
            }
            
            connect()
        }, currentReconnectDelay, TimeUnit.MILLISECONDS)
    }
    
    // Constants for guild verification and retry
    private val GUILD_RETRY_ATTEMPTS = 3
    private val GUILD_RETRY_DELAY_MS = 5000L
    
    /**
     * Register slash commands with Discord
     */
    private fun registerCommands() {
        val config = ArgusConfig.get()
        if (guild == null) {
            logger.error("Guild is null, cannot register commands")
            return
        }
        
        try {
            logger.info("Registering slash commands for guild: ${guild!!.name} (${guild!!.id})")

            val commandsToRegister = mutableListOf<CommandData>()
            commandsToRegister.add(WhitelistCommands.register(config.discord)) // Corrected: Call static register method
            commandsToRegister.add(GlobalBanCommand.register()) 

            // Register all commands
            guild!!.updateCommands().addCommands(commandsToRegister).queue(
                { logger.info("Successfully registered ${commandsToRegister.size} global commands.") },
                { error -> logger.error("Failed to register global commands", error) }
            )

        } catch (e: Exception) {
            logger.error("Exception during command registration: ${e.message}", e)
        }
    }
    
    /**
     * Register a command handler
     */
    fun registerCommandHandler(subcommand: String, handler: CommandHandler) {
        commandHandlers[subcommand] = handler
        logger.info("Registered handler for subcommand: $subcommand")
    }
    
    /**
     * Handle slash command interactions
     */
    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!event.isFromGuild) return

        // Basic rate limiting
        if (!commandRateLimiter.tryAcquire()) { 
            logger.warn("Rate limit exceeded for user ${event.user.id} (${event.user.name}) on command ${event.fullCommandName}")
            event.reply("You're sending commands too quickly. Please wait a moment and try again.")
            return
        }

        // Log the command attempt
        val optionsString = event.options.joinToString(" ") { "${it.name}:${it.asString}" }
        logger.info("User ${event.user.name} (${event.user.id}) used command: /${event.name} ${event.subcommandName ?: ""} $optionsString in guild ${event.guild?.name}")

        // Defer reply for all commands to avoid timeouts if processing takes time
        // event.deferReply().queue() // Deferring is now handled within individual command handlers if needed

        when (event.name) {
            "whitelist" -> {
                val subcommandName = event.subcommandName
                if (subcommandName != null) {
                    val handler = commandHandlers[subcommandName]
                    if (handler != null) {
                        // Permission check before executing
                        if (!hasRequiredRole(event.member)) {
                            logger.warn("User ${event.user.name} (${event.user.id}) attempted to use restricted command '/whitelist $subcommandName' without permission.")
                            event.reply("You do not have permission to use this command.").setEphemeral(true).queue()
                            return
                        }
                        ThreadPools.discordCommandExecutor.execute {
                            try {
                                handler.execute(event, event.options)    
                            } catch (e: Exception) {
                                logger.error("Error executing whitelist command $subcommandName for user ${event.user.name}", e)
                                if (event.isAcknowledged) {
                                    event.hook.editOriginal("An unexpected error occurred. Please check the logs.").queue()
                                } else {
                                    event.reply("An unexpected error occurred. Please check the logs.").setEphemeral(true).queue()
                                }
                            }
                        }
                    } else {
                        logger.warn("No handler found for subcommand: $subcommandName")
                        event.reply("Unknown subcommand for /whitelist.").setEphemeral(true).queue()
                    }
                } else {
                    logger.warn("Received /whitelist command without a subcommand.")
                    event.reply("Please specify a subcommand for /whitelist.").setEphemeral(true).queue()
                }
            }
            "ban" -> {
                // Permission check before executing (GlobalBanCommand's register() already sets default perms, but an extra check here is fine)
                if (!hasRequiredRole(event.member) && !event.member!!.hasPermission(Permission.BAN_MEMBERS)) {
                     logger.warn("User ${event.user.name} (${event.user.id}) attempted to use restricted command '/ban' without permission.")
                     event.reply("You do not have permission to use this command.").setEphemeral(true).queue()
                     return
                }
                globalBanCommand.handleCommand(event)
            }
            else -> {
                logger.warn("Received unknown slash command: ${event.name}")
                event.reply("Unknown command.").setEphemeral(true).queue()
            }
        }
    }

    /**
     * Handle guild member leave events
     * This is triggered when a user leaves or is kicked from the server
     */
    override fun onGuildMemberRemove(event: net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent) {
        val user = event.user
        logger.info("User left server: ${user.name} (${user.id})")
        
        try {
            // Handle the user leaving in the whitelist service
            val whitelistService = WhitelistService.getInstance()
            val success = whitelistService.handleUserLeft(user.id)
            
            if (success != null) {
                logger.info("Successfully processed server leave for ${user.name}, updated database records")
                
                // Get the configuration for auto-removal
                val config = ArgusConfig.get()
                if (config.whitelist.autoRemoveOnLeave) {
                    logger.info("Auto-removal on leave is enabled, removing ${user.name} from whitelist")
                    
                    // Get all Minecraft accounts for this Discord user
                    val minecraftAccounts = whitelistService.getMinecraftAccountsForDiscordUser(user.id)
                    
                    // Remove each account from the whitelist
                    minecraftAccounts.forEach { account ->
                        logger.info("Removing ${account.username} from whitelist due to Discord user ${user.name} leaving")
                        whitelistService.removeFromWhitelist(account.uuid, user.id)
                    }
                    
                    logger.info("Removed ${minecraftAccounts.size} Minecraft accounts from whitelist for user ${user.name}")
                } else {
                    logger.info("Auto-removal on leave is disabled, ${user.name} remains whitelisted")
                }
            } else {
                logger.warn("Failed to process server leave for ${user.name}")
            }
        } catch (e: Exception) {
            logger.error("Error handling user leave event for ${user.name}", e)
        }
    }
    
    /**
     * Check if a member has any of the required admin roles
     */
    private fun hasRequiredRole(member: Member?): Boolean {
        if (member == null) return false
        
        // Server owner always has permission
        if (member.isOwner) return true
        
        // Check for admin roles
        val roles = member.roles.map { it.name }
        return roles.any { adminRoles.contains(it) }
    }
    
    /**
     * Role types supported by the system
     */
    enum class RoleType {
        ADMIN, PATRON, ADULT
    }
    
    /**
     * Retrieves a member by their Discord ID
     * Returns null if retrieval fails
     */
    private fun getMemberById(discordId: String): Member? {
        try {
            val guild = this.guild ?: return null
            return guild.retrieveMemberById(discordId).complete()
        } catch (e: Exception) {
            logger.warn("Error retrieving member $discordId: ${e.message}")
            return null
        }
    }
    
    /**
     * Generic method to check if a user has a specific role
     */
    private fun hasRole(discordId: String, roleName: String, adminFallback: Boolean = false): Boolean {
        val member = getMemberById(discordId) ?: return false
        val hasSpecificRole = member.roles.any { it.name == roleName }
        return hasSpecificRole || (adminFallback && hasRequiredRole(member))
    }
    
    /**
     * Check if a user has any of the specified roles
     */
    private fun hasAnyRoles(discordId: String, roleNames: List<String>, adminFallback: Boolean = false): Boolean {
        val member = getMemberById(discordId) ?: return false
        val memberRoles = member.roles.map { it.name }
        val hasAny = roleNames.any { memberRoles.contains(it) }
        return hasAny || (adminFallback && hasRequiredRole(member))
    }
    
    /**
     * Check if a user has admin permissions using their Discord ID
     * This is useful for services that need to check permissions without a Member object
     */
    fun hasAdminPermission(discordId: String): Boolean {
        val member = getMemberById(discordId) ?: return false
        return hasRequiredRole(member)
    }
    
    /**
     * Check if a user has a specific role type
     */
    fun hasRoleType(discordId: String, type: RoleType): Boolean {
        return when(type) {
            RoleType.ADMIN -> hasAdminPermission(discordId)
            RoleType.PATRON -> hasPatronRole(discordId)
            RoleType.ADULT -> hasAdultRole(discordId)
        }
    }
    
    /**
     * Get the admin role names from config
     */
    fun getAdminRoleNames(): List<String> {
        return adminRoles
    }
    
    /**
     * Check if a user has patron role using their Discord ID
     */
    fun hasPatronRole(discordId: String): Boolean {
        return hasRole(discordId, patronRole, true)
    }
    
    /**
     * Check if a user has the adult role using their Discord ID
     */
    fun hasAdultRole(discordId: String): Boolean {
        return hasRole(discordId, adultRole, true)
    }
    
    /**
     * Get patron role name from config
     */
    fun getPatronRoleName(): String {
        return patronRole
    }
    
    /**
     * Get adult role name from config
     */
    fun getAdultRoleName(): String {
        return adultRole
    }
    
    /**
     * Format a Discord ID for display in embeds, properly handling special IDs
     * This will convert system user IDs (-2) to "System" and unmapped IDs (-1) to "Unmapped"
     * Normal Discord IDs will be formatted as mentions: <@123456789>
     *
     * @param discordId The Discord ID to format, can be null
     * @param defaultText Text to display if the ID is null (default: "Not Linked")
     * @return Properly formatted text for display in Discord embeds
     */
    fun formatDiscordMention(discordId: Long?, defaultText: String = "Not Linked"): String {
        return when (discordId) {
            null -> defaultText
            dev.butterflysky.db.WhitelistDatabase.SYSTEM_USER_ID -> "System"
            dev.butterflysky.db.WhitelistDatabase.UNMAPPED_DISCORD_ID -> "Unmapped"
            else -> "<@$discordId>"
        }
    }

    /**
     * Format a Discord ID (as String) for display in embeds, properly handling special IDs
     * This will convert system user IDs (-2) to "System" and unmapped IDs (-1) to "Unmapped"
     * Normal Discord IDs will be formatted as mentions: <@123456789>
     *
     * @param discordIdStr The Discord ID as a string to format, can be null
     * @param defaultText Text to display if the ID is null or invalid (default: "Not Linked")
     * @return Properly formatted text for display in Discord embeds
     */
    fun formatDiscordMention(discordIdStr: String?, defaultText: String = "Not Linked"): String {
        if (discordIdStr == null) return defaultText
        
        return try {
            val discordId = discordIdStr.toLongOrNull()
            if (discordId != null) {
                formatDiscordMention(discordId, defaultText)
            } else {
                discordIdStr // Not a number, return as is
            }
        } catch (e: Exception) {
            discordIdStr // Not a valid number, return as is
        }
    }
    
    /**
     * Sends a message to the configured logging channel with mentions suppressed
     * 
     * @param message The message text to send
     * @return True if the message was sent successfully, false otherwise
     */
    fun sendLogMessage(message: String): Boolean {
        if (!isConnected()) {
            logger.warn("Cannot send log message: Discord not connected")
            return false
        }
        
        val guild = this.guild ?: return false
        
        try {
            // Find the logging channel
            val channel = guild.getTextChannelsByName(loggingChannel, true).firstOrNull()
            
            if (channel == null) {
                logger.warn("Logging channel '$loggingChannel' not found")
                return false
            }
            
            // Send the message with all mentions suppressed
            channel.sendMessage(message)
                .setAllowedMentions(java.util.Collections.emptyList()) // Make it silent - no pings
                .queue(
                    { logger.debug("Sent log message to #$loggingChannel") },
                    { error -> logger.error("Error sending message to #$loggingChannel", error) }
                )
            
            return true
        } catch (e: Exception) {
            logger.error("Exception sending log message to channel #$loggingChannel", e)
            return false
        }
    }
    
    /**
     * Sends an audit log event to the configured logging channel with user-friendly formatting
     * 
     * @param actionType The type of action that occurred
     * @param entityType The type of entity affected
     * @param entityName The name or identifier of the affected entity
     * @param performedBy The user who performed the action, or null for system actions
     * @param details Additional details about the action
     * @return True if the message was sent successfully, false otherwise
     */
    fun sendAuditLogMessage(
        actionType: WhitelistDatabase.AuditActionType,
        entityType: WhitelistDatabase.EntityType,
        entityName: String,
        performedBy: WhitelistDatabase.DiscordUser?,
        details: String
    ): Boolean {
        if (!isConnected()) {
            return false
        }
        
        // Format the message for Discord display
        val performedByText = if (performedBy == null || performedBy.id.value == WhitelistDatabase.SYSTEM_USER_ID) {
            "System"
        } else {
            formatDiscordMention(performedBy.id.value)
        }
        
        val formattedMessage = buildString {
            append("**")
            append(actionType.displayName)
            append("**: ")
            
            when (entityType) {
                WhitelistDatabase.EntityType.MINECRAFT_USER -> append("Minecraft player ")
                WhitelistDatabase.EntityType.DISCORD_USER -> append("Discord user ")
                WhitelistDatabase.EntityType.WHITELIST_APPLICATION -> append("Application ")
            }
            
            append("**")
            append(entityName)
            append("**")
            
            if (performedBy != null) {
                append(" by ")
                append(performedByText)
            }
            
            if (details.isNotEmpty()) {
                append("\n> ")
                append(details)
            }
        }
        
        return sendLogMessage(formattedMessage)
    }
    
    /**
     * Shutdown the Discord service
     */
    fun shutdown() {
        try {
            logger.info("Shutting down Discord service")
            reconnectTask?.cancel(true)
            
            // Shutdown JDA with a timeout
            jda?.shutdown()
            try {
                // Give JDA up to 10 seconds to disconnect
                if (jda?.awaitShutdown(10, TimeUnit.SECONDS) == false) {
                    logger.warn("JDA did not shut down in time, forcing shutdown")
                    jda?.shutdownNow()
                }
            } catch (e: InterruptedException) {
                logger.error("Interrupted while waiting for JDA shutdown", e)
                Thread.currentThread().interrupt()
                jda?.shutdownNow()
            }
            
            logger.info("Discord service shutdown complete")
        } catch (e: Exception) {
            logger.error("Error during Discord service shutdown", e)
            try {
                jda?.shutdownNow()
            } catch (e2: Exception) {
                logger.error("Error during forced shutdown", e2)
            }
        }
    }
    
    /**
     * Interface for command handlers
     */
    interface CommandHandler {
        fun execute(event: SlashCommandInteractionEvent, options: List<net.dv8tion.jda.api.interactions.commands.OptionMapping>)
    }
}
