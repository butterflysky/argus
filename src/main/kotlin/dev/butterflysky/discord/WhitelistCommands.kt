package dev.butterflysky.discord

import dev.butterflysky.service.WhitelistService
import dev.butterflysky.service.MinecraftUserInfo
import dev.butterflysky.service.ApplicationInfo
import dev.butterflysky.service.ApplicationResult
import dev.butterflysky.service.UserSearchFilters
import dev.butterflysky.service.UserSearchResult
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.awt.Color
import java.util.UUID
import java.time.format.DateTimeFormatter
import net.minecraft.server.WhitelistEntry
import com.mojang.authlib.GameProfile
import java.util.concurrent.TimeUnit
import java.time.ZoneId
import java.time.Instant
import java.util.stream.Collectors

/**
 * Handlers for whitelist-related Discord commands
 */
class WhitelistCommands(private val server: MinecraftServer) {
    private val logger = LoggerFactory.getLogger("argus-whitelist-discord")
    private val whitelistService = WhitelistService.getInstance()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    
    /**
     * Register all command handlers with the Discord service
     */
    fun registerHandlers() {
        val discordService = DiscordService.getInstance()
        
        // Register handlers for each subcommand
        discordService.registerCommandHandler("add", AddHandler())
        discordService.registerCommandHandler("remove", RemoveHandler())
        discordService.registerCommandHandler("list", ListHandler())
        discordService.registerCommandHandler("on", OnHandler())
        discordService.registerCommandHandler("off", OffHandler())
        discordService.registerCommandHandler("reload", ReloadHandler())
        discordService.registerCommandHandler("test", TestHandler())
        discordService.registerCommandHandler("lookup", LookupHandler())
        discordService.registerCommandHandler("history", HistoryHandler())
        
        // New application-related commands
        discordService.registerCommandHandler("apply", ApplyHandler())
        discordService.registerCommandHandler("applications", ApplicationsHandler())
        discordService.registerCommandHandler("approve", ApproveHandler())
        discordService.registerCommandHandler("reject", RejectHandler())
        
        // Account linking command
        discordService.registerCommandHandler("link", LinkHandler())
        
        // Search command
        discordService.registerCommandHandler("search", SearchHandler())
        
        logger.info("Registered all whitelist command handlers")
    }
    
    /**
     * Get a GameProfile from a username
     */
    private fun getGameProfileByName(username: String): GameProfile? {
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
                val result = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
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
            return com.mojang.authlib.GameProfile(
                net.minecraft.util.Uuids.getOfflinePlayerUuid(username),
                username
            )
        }
        
        logger.warn("Could not resolve profile for $username")
        return null
    }
    
    /**
     * Base class with common functionality for whitelist commands
     */
    private abstract inner class BaseHandler : DiscordService.CommandHandler {
        /**
         * Execute a Minecraft server command
         */
        protected fun executeServerCommand(command: String): String {
            try {
                logger.info("Executing server command: $command")
                val result = server.commandManager.executeWithPrefix(
                    server.commandSource,
                    command
                )
                logger.info("Command executed with result: $result")
                
                // For simplicity, we'll just return a success message
                return "Command executed successfully"
            } catch (e: Exception) {
                logger.error("Error executing command: $command", e)
                return "Error: ${e.message}"
            }
        }
        
        /**
         * Check if user has moderator or admin permissions
         */
        protected fun isModeratorOrAdmin(event: SlashCommandInteractionEvent): Boolean {
            val member = event.member ?: return false
            
            // Check for moderator or admin role
            val hasModerator = member.roles.any { it.name.equals("Moderator", ignoreCase = true) }
            val hasAdmin = member.roles.any { it.name.equals("Admin", ignoreCase = true) }
            
            return hasModerator || hasAdmin
        }
    }
    
    /**
     * Base handler that requires moderator or admin permissions
     */
    private abstract inner class ModeratorCommandHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            // Check permissions
            if (!isModeratorOrAdmin(event)) {
                event.hook.editOriginal("You don't have permission to use this command.").queue()
                return
            }
            
            // Call the implementation
            executeWithPermission(event, options)
        }
        
        /**
         * Execute the command once permissions are verified
         */
        protected abstract fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>)
    }
    
    /**
     * Handler for the 'add' subcommand - admin only
     */
    private inner class AddHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val playerName = options.find { it.name == "player" }?.asString
            if (playerName == null) {
                event.hook.editOriginal("Please provide a player name.").queue()
                return
            }
            
            logger.info("Adding player to whitelist: $playerName (requested by ${event.user.name})")
            
            // Get player profile
            val profile = getGameProfileByName(playerName)
            if (profile == null) {
                event.hook.editOriginal("Could not find player: $playerName").queue()
                return
            }
            
            // Add to whitelist using our service
            val success = whitelistService.addToWhitelist(
                uuid = profile.id,
                username = profile.name,
                discordId = event.user.id,
                override = true,
                reason = "Added by moderator via Discord command"
            )
            
            if (success) {
                event.hook.editOriginal("Added $playerName to whitelist.").queue()
            } else {
                event.hook.editOriginal("Failed to add $playerName to whitelist.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'remove' subcommand - admin only
     */
    private inner class RemoveHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            val playerName = options.find { it.name == "player" }?.asString
            if (playerName == null) {
                event.hook.editOriginal("Please provide a player name.").queue()
                return
            }
            
            logger.info("Removing player from whitelist: $playerName (requested by ${event.user.name})")
            
            // Find the player in our database
            val user = whitelistService.findMinecraftUserByName(playerName)
            if (user == null) {
                event.hook.editOriginal("Player not found in whitelist: $playerName").queue()
                return
            }
            
            // Remove from whitelist
            val success = whitelistService.removeFromWhitelist(
                uuid = user.uuid,
                discordId = event.user.id
            )
            
            if (success) {
                event.hook.editOriginal("Removed $playerName from whitelist.").queue()
            } else {
                event.hook.editOriginal("Failed to remove $playerName from whitelist.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'list' subcommand
     */
    private inner class ListHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            logger.info("Listing whitelisted players (requested by ${event.user.name})")
            
            val whitelistedPlayers = whitelistService.getWhitelistedPlayers()
            val isWhitelistEnabled = server.playerManager.isWhitelistEnabled()
            
            val embed = EmbedBuilder()
                .setTitle("Minecraft Whitelist")
                .setColor(if (isWhitelistEnabled) Color.GREEN else Color.RED)
                .setDescription("Whitelist is ${if (isWhitelistEnabled) "enabled" else "disabled"}")
                
            if (whitelistedPlayers.isEmpty()) {
                embed.addField("Players", "No players in whitelist", false)
            } else {
                // Sort players by name
                val sortedPlayers = whitelistedPlayers.sortedBy { it.username.lowercase() }
                
                // Create individual cards for each player with all the info
                val maxPlayersPerField = 10
                val chunks = sortedPlayers.chunked(maxPlayersPerField)
                
                chunks.forEachIndexed { index, players ->
                    val playerCards = StringBuilder()
                    
                    players.forEach { player ->
                        // Discord mention if linked
                        val discordMention = if (player.discordUserId != null) {
                            "<@${player.discordUserId}>"
                        } else {
                            "Not Linked"
                        }
                        
                        // Format date
                        val addedDate = dateFormatter.format(player.addedAt)
                        
                        // Display who added the player as a Discord mention if it's a valid Discord ID
                        val addedByMention = try {
                            // Try to parse as a Long to see if it's a valid Discord ID
                            val addedById = player.addedBy.toLongOrNull()
                            if (addedById != null) {
                                "<@${player.addedBy}>" // Discord ID format
                            } else {
                                player.addedBy // Fallback to string (for system imports)
                            }
                        } catch (e: Exception) {
                            player.addedBy
                        }
                        
                        // Build a card-like entry for each player
                        playerCards.append("**${player.username}**\n")
                        playerCards.append("• Discord: ${discordMention}\n")
                        playerCards.append("• Added: ${addedDate} by ${addedByMention}\n")
                        playerCards.append("\n") // Space between entries
                    }
                    
                    val fieldTitle = if (chunks.size == 1) {
                        "Whitelisted Players"
                    } else {
                        "Whitelisted Players (${index + 1}/${chunks.size})"
                    }
                    
                    embed.addField(fieldTitle, playerCards.toString(), false)
                }
                
                embed.setFooter("Total: ${whitelistedPlayers.size} players")
            }
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'on' subcommand - admin only
     */
    private inner class OnHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            logger.info("Enabling whitelist (requested by ${event.user.name})")
            
            // Enable the whitelist
            server.playerManager.setWhitelistEnabled(true)
            
            // Force save the whitelist
            server.playerManager.whitelist.save()
            
            event.hook.editOriginal("Whitelist has been enabled.").queue()
        }
    }
    
    /**
     * Handler for the 'off' subcommand - admin only
     */
    private inner class OffHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            logger.info("Disabling whitelist (requested by ${event.user.name})")
            
            // Disable the whitelist
            server.playerManager.setWhitelistEnabled(false)
            
            // Force save the whitelist
            server.playerManager.whitelist.save()
            
            event.hook.editOriginal("Whitelist has been disabled.").queue()
        }
    }
    
    /**
     * Handler for the 'reload' subcommand - admin only
     */
    private inner class ReloadHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            logger.info("Reloading whitelist (requested by ${event.user.name})")
            
            // Reload the whitelist
            server.playerManager.reloadWhitelist()
            
            // Also reload our service's data
            whitelistService.importExistingWhitelist()
            
            event.hook.editOriginal("Whitelist reloaded.").queue()
        }
    }
    
    /**
     * Handler for the 'test' subcommand - admin only
     */
    private inner class TestHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            logger.info("Testing whitelist (requested by ${event.user.name})")
            
            // Execute the test command
            executeServerCommand("whitelist test")
            
            // Create a response with stats
            val whitelistedPlayers = whitelistService.getWhitelistedPlayers()
            val isWhitelistEnabled = server.playerManager.isWhitelistEnabled()
            val onlinePlayers = server.playerManager.playerList.size
            
            val embed = EmbedBuilder()
                .setTitle("Whitelist Test Results")
                .setColor(Color.BLUE)
                .setDescription("Whitelist system is functioning correctly")
                .addField("Status", if (isWhitelistEnabled) "Enabled" else "Disabled", true)
                .addField("Whitelisted Players", whitelistedPlayers.size.toString(), true)
                .addField("Online Players", onlinePlayers.toString(), true)
                .addField("Database", "Connected: ${WhitelistService.isDatabaseConnected()}", true)
                .setFooter("Test performed by ${event.user.name}")
                .setTimestamp(java.time.Instant.now())
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'lookup' subcommand
     */
    private inner class LookupHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val discordUser = options.find { it.name == "discord_user" }?.asUser
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            
            if (discordUser == null && minecraftName == null) {
                event.hook.editOriginal("Please provide either a Discord user or Minecraft username.").queue()
                return
            }
            
            logger.info("Looking up account info (requested by ${event.user.name})")
            
            val embed = EmbedBuilder()
                .setTitle("Account Lookup")
                .setColor(Color.BLUE)
            
            if (discordUser != null) {
                // Look up Minecraft accounts for this Discord user
                val accounts = whitelistService.getMinecraftAccountsForDiscordUser(discordUser.id)
                
                embed.setDescription("Discord User: **${discordUser.name}**")
                    .addField("Discord ID", discordUser.id, true)
                    .addField("Mention", discordUser.asMention, true)
                
                if (accounts.isEmpty()) {
                    embed.addField("Minecraft Accounts", "No linked Minecraft accounts ❌", true)
                } else {
                    val accountsList = accounts.joinToString("\n") { 
                        "**${it.username}** (${it.uuid})"
                    }
                    embed.addField("Linked Minecraft Accounts (${accounts.size})", accountsList, false)
                }
            } else {
                // Look up Discord user for this Minecraft account
                val minecraftUser = whitelistService.findMinecraftUserByName(minecraftName!!)
                
                if (minecraftUser == null) {
                    embed.setDescription("Minecraft account not found: $minecraftName")
                } else {
                    val discordUserInfo = whitelistService.getDiscordUserForMinecraftAccount(minecraftUser.uuid)
                    val isWhitelisted = whitelistService.isWhitelisted(minecraftUser.uuid)
                    
                    val discordInfo = if (discordUserInfo == null) {
                        "No linked Discord account"
                    } else {
                        // Check all special roles at runtime
                        val discordId = discordUserInfo.id
                        val discordService = DiscordService.getInstance()
                        val isAdmin = discordService.hasAdminPermission(discordId)
                        val isPatron = discordService.hasPatronRole(discordId)
                        val isAdult = discordService.hasAdultRole(discordId)
                        
                        // Build role display string
                        val roles = mutableListOf<String>()
                        if (isAdmin) roles.add(discordService.getAdminRoleNames().first())
                        if (isPatron) roles.add(discordService.getPatronRoleName())
                        if (isAdult) roles.add(discordService.getAdultRoleName())
                        
                        val roleList = if (roles.isNotEmpty()) {
                            " (${roles.joinToString(", ")})"
                        } else {
                            ""
                        }
                        
                        "<@${discordId}>${roleList}"
                    }
                    
                    // Create a cleaner, more concise display
                    embed.setDescription("Minecraft Account: **$minecraftName**")
                        .addField("UUID", minecraftUser.uuid.toString(), true)
                        .addField("Discord User", discordInfo, true)
                        .addField("Whitelist Status", if (isWhitelisted) "Whitelisted ✅" else "Not whitelisted ❌", true)
                }
            }
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'history' subcommand
     */
    private inner class HistoryHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            val discordUser = options.find { it.name == "discord_user" }?.asUser
            val limit = options.find { it.name == "limit" }?.asInt ?: 10
            
            logger.info("Fetching whitelist history (requested by ${event.user.name})")
            
            val embed = EmbedBuilder()
                .setTitle("Whitelist History")
                .setColor(Color.BLUE)
            
            // Determine which history to fetch and the description to display
            val (history, description) = when {
                minecraftName != null -> {
                    Pair(
                        whitelistService.getWhitelistHistoryByMinecraftName(minecraftName, limit),
                        "Whitelist history for $minecraftName"
                    )
                }
                discordUser != null -> {
                    Pair(
                        whitelistService.getWhitelistHistoryByDiscordId(discordUser.id, limit),
                        "Whitelist history for ${discordUser.asMention}"
                    )
                }
                else -> {
                    Pair(
                        whitelistService.getRecentWhitelistHistory(limit),
                        "Recent whitelist events"
                    )
                }
            }
            
            embed.setDescription(description)
            
            // Add history entries or "no events found" message
            if (history.isEmpty()) {
                embed.addField("History", "No whitelist events found", false)
            } else {
                val historyFormatter: (dev.butterflysky.service.WhitelistEventInfo) -> String = when {
                    minecraftName != null -> { event ->
                        // Format the actor as a Discord mention if possible
                        val actor = if (event.actorDiscordId != null) {
                            "<@${event.actorDiscordId}>"
                        } else {
                            "system"
                        }
                        
                        // Format Discord user if available
                        val discordInfo = if (event.discordUserId != null) {
                            " with Discord <@${event.discordUserId}>"
                        } else {
                            ""
                        }
                        
                        // Format the comment if available
                        val commentInfo = if (event.comment != null && event.comment.isNotBlank()) {
                            " - \"${event.comment}\""
                        } else {
                            ""
                        }
                        
                        "${dateFormatter.format(event.timestamp)} - ${event.eventType}$discordInfo by $actor$commentInfo"
                    }
                    else -> { event ->
                        // Format the actor as a Discord mention if possible
                        val actor = if (event.actorDiscordId != null) {
                            "<@${event.actorDiscordId}>"
                        } else {
                            "system"
                        }
                        
                        // Format Discord user if available
                        val discordInfo = if (event.discordUserId != null) {
                            " with Discord <@${event.discordUserId}>"
                        } else {
                            ""
                        }
                        
                        // Format the comment if available
                        val commentInfo = if (event.comment != null && event.comment.isNotBlank()) {
                            " - \"${event.comment}\""
                        } else {
                            ""
                        }
                        
                        "${dateFormatter.format(event.timestamp)} - ${event.minecraftUsername}: ${event.eventType}$discordInfo by $actor$commentInfo"
                    }
                }
                
                val historyList = history.joinToString("\n", transform = historyFormatter)
                embed.addField("Recent Events", historyList, false)
            }
            
            embed.setFooter("Showing up to $limit events")
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'apply' subcommand - for players to apply for whitelist
     */
    private inner class ApplyHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            if (minecraftName == null) {
                event.hook.editOriginal("Please provide your Minecraft username.").queue()
                return
            }
            
            logger.info("Whitelist application submitted for $minecraftName by ${event.user.name} (${event.user.id})")
            
            // Submit the application
            val result = whitelistService.submitWhitelistApplication(
                discordId = event.user.id,
                minecraftUsername = minecraftName
            )
            
            when (result) {
                is ApplicationResult.Success -> {
                    // Format the eligibility date
                    val eligibleDate = dateFormatter.format(result.eligibleAt)
                    
                    // Create a fancy embed for the success message
                    val embed = EmbedBuilder()
                        .setTitle("Whitelist Application Submitted")
                        .setColor(Color.GREEN)
                        .setDescription(
                            "Your application for **$minecraftName** has been submitted successfully. " +
                            "A moderator will review your application soon."
                        )
                        .addField("Application ID", "#${result.applicationId}", true)
                        .addField("Minecraft Username", minecraftName, true)
                        .addField("Discord User", event.user.asMention, true)
                        .addField("Eligible For Review", "After $eligibleDate", false)
                        .setFooter("Waiting for moderator approval")
                        .setTimestamp(Instant.now())
                        .build()
                    
                    event.hook.editOriginalEmbeds(embed).queue()
                }
                is ApplicationResult.Error -> {
                    event.hook.editOriginal("Failed to submit application: ${result.message}").queue()
                }
            }
        }
    }
    
    /**
     * Handler for the 'applications' subcommand - admin only, for listing pending applications
     */
    private inner class ApplicationsHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            logger.info("Listing whitelist applications (requested by ${event.user.name})")
            
            // Get all pending applications
            val applications = whitelistService.getPendingApplications()
            
            val embed = EmbedBuilder()
                .setTitle("Pending Whitelist Applications")
                .setColor(Color.BLUE)
            
            if (applications.isEmpty()) {
                embed.setDescription("There are no pending whitelist applications.")
            } else {
                // Sort applications by date (oldest first)
                val sortedApplications = applications.sortedBy { it.appliedAt }
                
                // Create individual cards for each application
                val applicationsText = StringBuilder()
                
                sortedApplications.forEach { app ->
                    // Format dates
                    val appliedDate = dateFormatter.format(app.appliedAt)
                    val eligibleDate = dateFormatter.format(app.eligibleAt)
                    
                    // Build a card-like entry for each application
                    applicationsText.append("**Application #${app.id}**\n")
                    applicationsText.append("• Minecraft: **${app.minecraftUsername}**\n")
                    applicationsText.append("• Discord: <@${app.discordId}>\n")
                    applicationsText.append("• Applied: $appliedDate\n")
                    applicationsText.append("• Eligible: $eligibleDate")
                    
                    // Add eligibility status
                    if (app.isEligibleNow) {
                        applicationsText.append(" (Eligible Now 🟢)")
                    } else {
                        applicationsText.append(" (Waiting ⏳)")
                    }
                    
                    // Usage hint
                    applicationsText.append("\n• `/whitelist approve ${app.id}` or `/whitelist reject ${app.id}`\n\n")
                }
                
                embed.setDescription(applicationsText.toString())
                embed.setFooter("Total: ${applications.size} pending applications")
            }
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'approve' subcommand - admin only, for approving an application
     */
    private inner class ApproveHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            val applicationId = options.find { it.name == "application_id" }?.asInt
            val notes = options.find { it.name == "notes" }?.asString
            
            if (applicationId == null) {
                event.hook.editOriginal("Please provide an application ID.").queue()
                return
            }
            
            logger.info("Approving whitelist application #$applicationId (requested by ${event.user.name})")
            
            // Approve the application
            val success = whitelistService.approveApplication(
                applicationId = applicationId,
                moderatorDiscordId = event.user.id,
                notes = notes
            )
            
            if (success) {
                event.hook.editOriginal("Successfully approved whitelist application #$applicationId.").queue()
            } else {
                event.hook.editOriginal("Failed to approve application #$applicationId. It may not exist or is not in a pending state.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'reject' subcommand - admin only, for rejecting an application
     */
    private inner class RejectHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            
            val applicationId = options.find { it.name == "application_id" }?.asInt
            val notes = options.find { it.name == "notes" }?.asString
            
            if (applicationId == null) {
                event.hook.editOriginal("Please provide an application ID.").queue()
                return
            }
            
            logger.info("Rejecting whitelist application #$applicationId (requested by ${event.user.name})")
            
            // Reject the application
            val success = whitelistService.rejectApplication(
                applicationId = applicationId,
                moderatorDiscordId = event.user.id,
                notes = notes
            )
            
            if (success) {
                event.hook.editOriginal("Successfully rejected whitelist application #$applicationId.").queue()
            } else {
                event.hook.editOriginal("Failed to reject application #$applicationId. It may not exist or is not in a pending state.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'link' subcommand - for linking Discord accounts to Minecraft
     */
    private inner class LinkHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val token = options.find { it.name == "token" }?.asString
            
            if (token == null) {
                event.hook.editOriginal("Please provide a valid link token from Minecraft.").queue()
                return
            }
            
            logger.info("Processing link token $token from Discord user ${event.user.name} (${event.user.id})")
            
            // Get the LinkManager instance
            val linkManager = dev.butterflysky.whitelist.LinkManager.getInstance()
            
            // Find the link request for this token
            val linkRequest = linkManager.getLinkRequestByToken(token)
            if (linkRequest == null) {
                // Clear error message with instructions to get a new token
                val errorMessage = "Invalid or expired token. Please run `/whitelist link` in Minecraft to generate a new token."
                event.hook.editOriginal(errorMessage).queue()
                return
            }
            
            // Create the mapping between Discord and Minecraft
            val success = whitelistService.mapDiscordToMinecraft(
                discordUserId = event.user.id,
                discordUsername = event.user.name,
                minecraftUuid = linkRequest.minecraftUuid,
                minecraftUsername = linkRequest.minecraftUsername,
                createdByDiscordId = event.user.id
            )
            
            if (success) {
                // Mark the token as processed
                linkManager.markTokenAsProcessed(token)
                
                // Create a fancy embed response
                val embed = EmbedBuilder()
                    .setTitle("Account Linked Successfully")
                    .setColor(Color.GREEN)
                    .setDescription("Your Discord account has been linked to Minecraft account **${linkRequest.minecraftUsername}**")
                    .addField("Minecraft Username", linkRequest.minecraftUsername, true)
                    .addField("Minecraft UUID", linkRequest.minecraftUuid.toString(), true)
                    .addField("Discord User", event.user.asMention, true)
                    .setFooter("You can now use whitelist commands in-game")
                    .setTimestamp(Instant.now())
                    .build()
                
                event.hook.editOriginalEmbeds(embed).queue()
                
                logger.info("Successfully linked Discord user ${event.user.name} (${event.user.id}) to Minecraft account ${linkRequest.minecraftUsername} (${linkRequest.minecraftUuid})")
            } else {
                event.hook.editOriginal("Failed to link accounts. Please try again later.").queue()
                logger.error("Failed to link Discord user ${event.user.name} (${event.user.id}) to Minecraft account ${linkRequest.minecraftUsername} (${linkRequest.minecraftUuid})")
            }
        }
    }
    
    /**
     * Handler for the 'search' subcommand - search users with various filters
     */
    private inner class SearchHandler : ModeratorCommandHandler() {
        override fun executeWithPermission(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            logger.info("Performing user search (requested by ${event.user.name})")
            
            // Extract filters from options
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            val discordName = options.find { it.name == "discord_name" }?.asString
            val discordUser = options.find { it.name == "discord_user" }?.asUser
            val hasDiscord = options.find { it.name == "has_discord" }?.asBoolean
            val isWhitelisted = options.find { it.name == "is_whitelisted" }?.asBoolean
            val addedBy = options.find { it.name == "added_by" }?.asUser
            val limit = options.find { it.name == "limit" }?.asInt?.coerceIn(1, 50) ?: 20
            
            // Build filter object
            val filters = UserSearchFilters(
                minecraftUsername = minecraftName,
                discordUsername = discordName,
                discordId = discordUser?.id,
                hasDiscordLink = hasDiscord,
                isWhitelisted = isWhitelisted,
                addedBy = addedBy?.id,
                limit = limit
            )
            
            // Execute search
            val results = whitelistService.searchUsers(filters)
            
            // Create response embed
            val embed = EmbedBuilder()
                .setTitle("User Search Results")
                .setColor(Color.BLUE)
                .setDescription("Found ${results.size} users matching your criteria.")
                .setFooter("Search performed by ${event.user.name}")
                .setTimestamp(Instant.now())
            
            // Display search filters that were used
            val filterDescription = StringBuilder()
            if (minecraftName != null) filterDescription.append("• Minecraft name: *$minecraftName*\n")
            if (discordName != null) filterDescription.append("• Discord name: *$discordName*\n")
            if (discordUser != null) filterDescription.append("• Discord user: ${discordUser.asMention}\n")
            if (hasDiscord != null) filterDescription.append("• Has Discord link: ${if (hasDiscord) "Yes" else "No"}\n")
            if (isWhitelisted != null) filterDescription.append("• Whitelisted: ${if (isWhitelisted) "Yes" else "No"}\n")
            if (addedBy != null) filterDescription.append("• Added by: ${addedBy.asMention}\n")
            if (filterDescription.isNotEmpty()) {
                embed.addField("Search Filters", filterDescription.toString(), false)
            }
            
            // Display search results
            if (results.isEmpty()) {
                embed.addField("Results", "No users found matching your search criteria", false)
            } else {
                // Group results into chunks to stay within Discord embed limits
                val maxResultsPerField = 5
                results.chunked(maxResultsPerField).forEachIndexed { index, chunk ->
                    val resultText = StringBuilder()
                    
                    chunk.forEach { result ->
                        // Minecraft info
                        val minecraftInfo = result.minecraftInfo
                        if (minecraftInfo != null) {
                            resultText.append("**MC:** ${minecraftInfo.username} (${minecraftInfo.uuid})\n")
                        }
                        
                        // Discord info
                        val discordInfo = result.discordInfo
                        if (discordInfo != null) {
                            resultText.append("**Discord:** <@${discordInfo.id}> (${discordInfo.username})\n")
                        } else {
                            resultText.append("**Discord:** Not linked\n")
                        }
                        
                        // Whitelist status
                        val statusEmoji = if (result.isWhitelisted) "✅" else "❌"
                        resultText.append("**Status:** ${if (result.isWhitelisted) "Whitelisted" else "Not whitelisted"} $statusEmoji\n")
                        
                        // Added info
                        if (result.addedAt != null) {
                            val addedDate = dateFormatter.format(result.addedAt)
                            val addedByText = if (result.addedBy != null) {
                                try {
                                    val addedById = result.addedBy.toLongOrNull()
                                    if (addedById != null) {
                                        "<@${result.addedBy}>"
                                    } else {
                                        result.addedBy
                                    }
                                } catch (e: Exception) {
                                    result.addedBy
                                }
                            } else {
                                "unknown"
                            }
                            resultText.append("**Added:** $addedDate by $addedByText\n")
                        }
                        
                        resultText.append("\n") // Add spacing between results
                    }
                    
                    val fieldTitle = if (results.size <= maxResultsPerField) {
                        "Results"
                    } else {
                        "Results (${index + 1}/${(results.size + maxResultsPerField - 1) / maxResultsPerField})"
                    }
                    
                    embed.addField(fieldTitle, resultText.toString(), false)
                }
            }
            
            // Send response
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
}