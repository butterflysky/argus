package dev.butterflysky.discord

import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.service.WhitelistService
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
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import net.minecraft.server.MinecraftServer
import java.util.UUID

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
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var reconnectTask: ScheduledFuture<*>? = null
    private var currentReconnectDelay: Long = 0
    private var adminRoles: List<String> = listOf()
    private var patronRole: String = ""
    private var adultRole: String = ""
    
    private var minecraftServer: MinecraftServer? = null
    private val whitelistService = WhitelistService.getInstance()
    
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
        minecraftServer = server
        
        // Initialize whitelist service with server
        try {
            whitelistService.initialize(server)
        } catch (e: Exception) {
            logger.error("Failed to initialize whitelist service", e)
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
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(this)
            
            // Build the JDA instance and add a ready listener to handle guild setup
            jda = jdaBuilder.build()
            
            // Add a ready listener to handle guild setup and command registration
            jda?.addEventListener(object : net.dv8tion.jda.api.hooks.EventListener {
                override fun onEvent(event: net.dv8tion.jda.api.events.GenericEvent) {
                    if (event is net.dv8tion.jda.api.events.session.ReadyEvent) {
                        // Get the guild from config
                        val guildId = config.discord.guildId
                        guild = jda?.getGuildById(guildId)
                        
                        if (guild == null) {
                            logger.error("Could not find guild with ID $guildId")
                            scheduleReconnect()
                            return
                        }
                        
                        logger.info("Connected to Discord guild: ${guild?.name}")
                        
                        // Register slash commands after we're fully connected
                        registerCommands()
                        
                        // Reset reconnect delay on successful connection
                        currentReconnectDelay = 0
                        
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
    
    /**
     * Register slash commands with Discord
     */
    private fun registerCommands() {
        val guild = this.guild ?: return
        
        // Log that we're starting command registration
        logger.info("Registering Discord slash commands for guild ${guild.name} (${guild.id})")
        
        // Clear existing commands with complete callback
        try {
            guild.updateCommands().queue(
                { logger.info("Successfully cleared existing commands, now registering new commands") },
                { error -> logger.error("Failed to clear existing commands", error) }
            )
        } catch (e: Exception) {
            logger.error("Exception while clearing existing commands", e)
        }
        
        // Create the whitelist command with subcommands
        val whitelistCommand = Commands.slash("whitelist", "Manage the Minecraft server whitelist")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            
        // Add subcommand
        val addSubcommand = SubcommandData("add", "Add a player to the whitelist")
            .addOption(OptionType.STRING, "player", "The Minecraft username to add", true)
        
        // Remove subcommand
        val removeSubcommand = SubcommandData("remove", "Remove a player from the whitelist")
            .addOption(OptionType.STRING, "player", "The Minecraft username to remove", true)
        
        // List subcommand
        val listSubcommand = SubcommandData("list", "List all players on the whitelist")
        
        // On subcommand
        val onSubcommand = SubcommandData("on", "Enable the whitelist")
        
        // Off subcommand
        val offSubcommand = SubcommandData("off", "Disable the whitelist")
        
        // Reload subcommand
        val reloadSubcommand = SubcommandData("reload", "Reload the whitelist")
        
        // Test subcommand
        val testSubcommand = SubcommandData("test", "Test the whitelist functionality")
        
        // Lookup subcommand - find linked accounts
        val lookupSubcommand = SubcommandData("lookup", "Look up linked accounts")
            .addOption(OptionType.USER, "discord_user", "The Discord user to look up", false)
            .addOption(OptionType.STRING, "minecraft_name", "The Minecraft username to look up", false)
        
        // History subcommand - show whitelist history
        val historySubcommand = SubcommandData("history", "Show whitelist history")
            .addOption(OptionType.STRING, "minecraft_name", "The Minecraft username to show history for", false)
            .addOption(OptionType.USER, "discord_user", "The Discord user to show history for", false)
            .addOption(OptionType.INTEGER, "limit", "Maximum number of entries to show", false)
            
        // Apply subcommand - for players to apply for whitelist
        val applySubcommand = SubcommandData("apply", "Apply for whitelist with your Minecraft account")
            .addOption(OptionType.STRING, "minecraft_name", "Your Minecraft username", true)
            
        // Applications subcommand - for admins to view pending applications
        val applicationsSubcommand = SubcommandData("applications", "View pending whitelist applications")
            
        // Approve subcommand - for approving applications
        val approveSubcommand = SubcommandData("approve", "Approve a whitelist application")
            .addOption(OptionType.INTEGER, "application_id", "The ID of the application to approve", true)
            .addOption(OptionType.STRING, "notes", "Optional notes about the approval", false)
            
        // Reject subcommand - for rejecting applications
        val rejectSubcommand = SubcommandData("reject", "Reject a whitelist application")
            .addOption(OptionType.INTEGER, "application_id", "The ID of the application to reject", true)
            .addOption(OptionType.STRING, "notes", "Optional notes about the rejection", false)
            
        // Link subcommand - for linking Discord to Minecraft accounts
        val linkSubcommand = SubcommandData("link", "Link your Discord account to your Minecraft account")
            .addOption(OptionType.STRING, "token", "The token generated in Minecraft with /whitelist link", true)
            
        // Search subcommand - for searching users with various filters
        val searchSubcommand = SubcommandData("search", "Search for users with filters")
            .addOption(OptionType.STRING, "minecraft_name", "Search by Minecraft username (partial match)", false)
            .addOption(OptionType.STRING, "discord_name", "Search by Discord username (partial match)", false)
            .addOption(OptionType.USER, "discord_user", "Search by Discord user", false)
            .addOption(OptionType.BOOLEAN, "has_discord", "Filter by whether account has Discord link", false)
            .addOption(OptionType.BOOLEAN, "is_whitelisted", "Filter by whitelist status", false)
            .addOption(OptionType.USER, "added_by", "Filter by who added the user", false)
            .addOption(OptionType.INTEGER, "limit", "Maximum number of results (max 50)", false)
        
        whitelistCommand.addSubcommands(
            addSubcommand,
            applicationsSubcommand,
            applySubcommand,
            approveSubcommand,
            historySubcommand,
            linkSubcommand,
            listSubcommand,
            lookupSubcommand,
            offSubcommand,
            onSubcommand,
            rejectSubcommand,
            reloadSubcommand,
            removeSubcommand,
            searchSubcommand,
            testSubcommand
        )
        
        // Register the commands with Discord
        try {
            guild.upsertCommand(whitelistCommand).queue(
                { cmd -> 
                    logger.info("Successfully registered whitelist command with ID: ${cmd.id}")
                    logger.info("All ${whitelistCommand.subcommands.size} subcommands registered")
                    
                    // List all registered subcommands for debugging
                    whitelistCommand.subcommands.forEach { subcommand ->
                        logger.info("  - Registered subcommand: ${subcommand.name}")
                    }
                },
                { error ->
                    logger.error("Failed to register commands", error)
                }
            )
        } catch (e: Exception) {
            logger.error("Exception during command registration", e)
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
        val commandName = event.name
        
        if (commandName != "whitelist") {
            return
        }
        
        // Check if user has the required role
        if (!hasRequiredRole(event.member)) {
            event.reply("You don't have the required permissions to use this command.")
                .setEphemeral(true)
                .queue()
            logger.warn("User ${event.user.name} attempted to use whitelist command without permission")
            return
        }
        
        val subcommandName = event.subcommandName
        if (subcommandName == null) {
            event.reply("Invalid command format.")
                .setEphemeral(true)
                .queue()
            return
        }
        
        // Find the handler for this subcommand
        val handler = commandHandlers[subcommandName]
        if (handler == null) {
            event.reply("No handler registered for subcommand: $subcommandName")
                .setEphemeral(true)
                .queue()
            return
        }
        
        logger.info("Handling whitelist/$subcommandName command from ${event.user.name}")
        
        // Acknowledge the command immediately with ephemeral response (only visible to command invoker)
        event.deferReply(true).queue()
        
        // Execute the command in a separate thread to not block Discord
        Thread {
            try {
                handler.execute(event, event.options)
            } catch (e: Exception) {
                logger.error("Error executing command", e)
                event.hook.editOriginal("An error occurred while executing the command. Check the server logs.").queue()
            }
        }.start()
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
     * Shutdown the Discord service
     */
    fun shutdown() {
        try {
            reconnectTask?.cancel(true)
            scheduler.shutdown()
            jda?.shutdown()
            logger.info("Discord service shutdown")
        } catch (e: Exception) {
            logger.error("Error during Discord service shutdown", e)
        }
    }
    
    /**
     * Interface for command handlers
     */
    interface CommandHandler {
        fun execute(event: SlashCommandInteractionEvent, options: List<net.dv8tion.jda.api.interactions.commands.OptionMapping>)
    }
}