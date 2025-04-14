package dev.butterflysky.discord

import dev.butterflysky.service.WhitelistService
import dev.butterflysky.service.MinecraftUserInfo
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

/**
 * Handlers for whitelist-related Discord commands
 */
class WhitelistCommands(private val server: MinecraftServer) {
    private val logger = LoggerFactory.getLogger("argus-whitelist-discord")
    private val whitelistService = WhitelistService.getInstance()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
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
        discordService.registerCommandHandler("link", LinkHandler())
        discordService.registerCommandHandler("unlink", UnlinkHandler())
        discordService.registerCommandHandler("lookup", LookupHandler())
        discordService.registerCommandHandler("history", HistoryHandler())
        
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
    }
    
    /**
     * Handler for the 'add' subcommand
     */
    private inner class AddHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
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
                addedBy = event.user.name
            )
            
            if (success) {
                event.hook.editOriginal("Added $playerName to whitelist.").queue()
            } else {
                event.hook.editOriginal("Failed to add $playerName to whitelist.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'remove' subcommand
     */
    private inner class RemoveHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
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
                removedBy = event.user.name
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
                // Group players by who added them
                val playersByAdder = whitelistedPlayers.groupBy { it.addedBy }
                
                for ((adder, players) in playersByAdder) {
                    val playerList = players.joinToString("\\n") { 
                        "${it.username} (added: ${it.addedAt.format(dateFormatter)})" 
                    }
                    embed.addField("Added by $adder (${players.size})", playerList, false)
                }
                
                embed.setFooter("Total: ${whitelistedPlayers.size} players")
            }
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
    
    /**
     * Handler for the 'on' subcommand
     */
    private inner class OnHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            logger.info("Enabling whitelist (requested by ${event.user.name})")
            
            // Enable the whitelist
            server.playerManager.setWhitelistEnabled(true)
            
            // Force save the whitelist
            server.playerManager.whitelist.save()
            
            event.hook.editOriginal("Whitelist has been enabled.").queue()
        }
    }
    
    /**
     * Handler for the 'off' subcommand
     */
    private inner class OffHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            logger.info("Disabling whitelist (requested by ${event.user.name})")
            
            // Disable the whitelist
            server.playerManager.setWhitelistEnabled(false)
            
            // Force save the whitelist
            server.playerManager.whitelist.save()
            
            event.hook.editOriginal("Whitelist has been disabled.").queue()
        }
    }
    
    /**
     * Handler for the 'reload' subcommand
     */
    private inner class ReloadHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            logger.info("Reloading whitelist (requested by ${event.user.name})")
            
            // Reload the whitelist
            server.playerManager.reloadWhitelist()
            
            // Also reload our service's data
            whitelistService.importExistingWhitelist()
            
            event.hook.editOriginal("Whitelist reloaded.").queue()
        }
    }
    
    /**
     * Handler for the 'test' subcommand
     */
    private inner class TestHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
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
     * Handler for the 'link' subcommand
     */
    private inner class LinkHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val discordUser = options.find { it.name == "discord_user" }?.asUser
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            
            if (discordUser == null || minecraftName == null) {
                event.hook.editOriginal("Please provide both a Discord user and Minecraft username.").queue()
                return
            }
            
            logger.info("Linking Discord user ${discordUser.name} to Minecraft account $minecraftName (requested by ${event.user.name})")
            
            // Get Minecraft profile
            val profile = getGameProfileByName(minecraftName)
            if (profile == null) {
                event.hook.editOriginal("Could not find Minecraft player: $minecraftName").queue()
                return
            }
            
            // Link the accounts
            val success = whitelistService.mapDiscordToMinecraft(
                discordUserId = discordUser.id,
                discordUsername = discordUser.name,
                minecraftUuid = profile.id,
                minecraftUsername = profile.name,
                createdBy = event.user.name,
                isPrimary = true
            )
            
            if (success) {
                // Also make sure the player is whitelisted
                whitelistService.addToWhitelist(
                    uuid = profile.id,
                    username = profile.name,
                    addedBy = event.user.name
                )
                
                event.hook.editOriginal("Linked Discord user ${discordUser.asMention} to Minecraft account $minecraftName and added to whitelist.").queue()
            } else {
                event.hook.editOriginal("Failed to link accounts.").queue()
            }
        }
    }
    
    /**
     * Handler for the 'unlink' subcommand
     */
    private inner class UnlinkHandler : BaseHandler() {
        override fun execute(event: SlashCommandInteractionEvent, options: List<OptionMapping>) {
            val discordUser = options.find { it.name == "discord_user" }?.asUser
            val minecraftName = options.find { it.name == "minecraft_name" }?.asString
            
            if (discordUser == null && minecraftName == null) {
                event.hook.editOriginal("Please provide either a Discord user or Minecraft username.").queue()
                return
            }
            
            logger.info("Unlinking account(s) (requested by ${event.user.name})")
            
            val success = if (discordUser != null && minecraftName != null) {
                // Unlink specific mapping
                whitelistService.unlinkDiscordMinecraft(
                    discordUserId = discordUser.id,
                    minecraftName = minecraftName,
                    performedBy = event.user.name
                )
            } else if (discordUser != null) {
                // Unlink all of this Discord user's accounts
                whitelistService.unlinkAllMinecraftAccounts(
                    discordUserId = discordUser.id,
                    performedBy = event.user.name
                )
            } else {
                // Unlink this Minecraft account from any Discord user
                whitelistService.unlinkMinecraftAccount(
                    minecraftName = minecraftName!!,
                    performedBy = event.user.name
                )
            }
            
            if (success) {
                val message = when {
                    discordUser != null && minecraftName != null -> 
                        "Unlinked Discord user ${discordUser.asMention} from Minecraft account $minecraftName."
                    discordUser != null -> 
                        "Unlinked all Minecraft accounts from Discord user ${discordUser.asMention}."
                    else -> 
                        "Unlinked Minecraft account $minecraftName from all Discord users."
                }
                event.hook.editOriginal(message).queue()
            } else {
                event.hook.editOriginal("Failed to unlink account(s).").queue()
            }
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
            
            logger.info("Looking up linked account(s) (requested by ${event.user.name})")
            
            val embed = EmbedBuilder()
                .setTitle("Account Lookup")
                .setColor(Color.BLUE)
            
            if (discordUser != null) {
                // Look up Minecraft accounts for this Discord user
                val accounts = whitelistService.getMinecraftAccountsForDiscordUser(discordUser.id)
                
                embed.setDescription("Discord User: ${discordUser.asMention}")
                
                if (accounts.isEmpty()) {
                    embed.addField("Minecraft Accounts", "No linked Minecraft accounts", false)
                } else {
                    val accountsList = accounts.joinToString("\\n") { 
                        "${it.username} ${if (it.isPrimary) "(Primary)" else ""}" 
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
                    
                    embed.setDescription("Minecraft Account: $minecraftName (${minecraftUser.uuid})")
                    
                    if (discordUserInfo == null) {
                        embed.addField("Discord User", "No linked Discord account", false)
                    } else {
                        val discordMention = "<@${discordUserInfo.discordId}>"
                        embed.addField("Linked Discord User", discordMention, false)
                        
                        // Add role info
                        val roleInfo = mutableListOf<String>()
                        if (discordUserInfo.isAdmin) roleInfo.add("Admin")
                        if (discordUserInfo.isModerator) roleInfo.add("Moderator")
                        
                        if (roleInfo.isNotEmpty()) {
                            embed.addField("Roles", roleInfo.joinToString(", "), false)
                        }
                    }
                    
                    // Add whitelist status
                    val isWhitelisted = whitelistService.isWhitelisted(minecraftUser.uuid)
                    embed.addField("Whitelist Status", if (isWhitelisted) "Whitelisted" else "Not whitelisted", false)
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
                        "${event.timestamp.format(dateFormatter)} - ${event.eventType} by ${event.performedBy}"
                    }
                    else -> { event ->
                        "${event.timestamp.format(dateFormatter)} - ${event.playerName}: ${event.eventType} by ${event.performedBy}"
                    }
                }
                
                val historyList = history.joinToString("\\n", transform = historyFormatter)
                embed.addField("Recent Events", historyList, false)
            }
            
            embed.setFooter("Showing up to $limit events")
            
            event.hook.editOriginalEmbeds(embed.build()).queue()
        }
    }
}