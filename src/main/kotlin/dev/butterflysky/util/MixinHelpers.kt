package dev.butterflysky.util

import com.mojang.authlib.GameProfile
import dev.butterflysky.config.ArgusConfig
import dev.butterflysky.discord.DiscordService
import dev.butterflysky.service.DiscordUserInfo
import dev.butterflysky.service.WhitelistService
import dev.butterflysky.whitelist.LinkManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Utilities for mixins to use
 */
object MixinHelpers {
    private val LOGGER = LoggerFactory.getLogger("argus-mixins")
    private val WHITELIST_SERVICE = WhitelistService.getInstance()
    private val LINK_MANAGER = LinkManager.getInstance()
    private val DISCORD_SERVICE = DiscordService.getInstance()
    
    /**
     * Check if Discord integration is enabled and connected
     * 
     * @return true if Discord is enabled in config and currently connected
     */
    @JvmStatic
    fun isDiscordAvailable(): Boolean {
        val discordEnabled = ArgusConfig.get().discord.enabled
        val discordConnected = DISCORD_SERVICE.isConnected()
        return discordEnabled && discordConnected
    }
    
    /**
     * Check if a player has their Minecraft account linked to Discord
     * If not, send them a message with a token for linking.
     * If Discord is not enabled or connected, allow the action.
     * 
     * @param source The command source
     * @param commandName Name of the command being executed, for logging
     * @return true if account is linked or source is not a player (e.g., console), or Discord is disabled/disconnected
     */
    @JvmStatic
    fun checkDiscordLinkOrShowMessage(source: ServerCommandSource, commandName: String): Boolean {
        val playerProfile = source.player?.gameProfile
        
        // Allow console/RCON to execute regardless
        if (playerProfile == null) {
            LOGGER.info("[ARGUS] {} command executed from console or RCON", commandName)
            return true
        }
        
        // If Discord is not enabled or not connected, allow the command (fall back to vanilla behavior)
        if (!isDiscordAvailable()) {
            LOGGER.info("[ARGUS] Discord integration is disabled or not connected. Allowing {} command from player {}", 
                commandName, playerProfile.name)
            return true
        }
        
        // Check if player has a linked Discord account
        val discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.id)
        
        if (discordUser == null) {
            // Generate a token for linking
            val token = LINK_MANAGER.createLinkToken(playerProfile.id, playerProfile.name)
            
            // Send a message to the player explaining they need to link their Discord account
            val linkText = Text.literal("§c[Argus] §eYou need to link your Discord account to use $commandName commands.\n")
                .append(Text.literal("§ePlease run §b/whitelist link §eto generate a token, then use that token in Discord.\n"))
                .append(Text.literal("§7($commandName commands are restricted to users with linked Discord accounts)"))
            
            source.sendFeedback({ linkText }, false)
            
            LOGGER.info("[ARGUS] Player {} attempted to use {} command without linked Discord account", 
                playerProfile.name, commandName)
            
            return false
        }
        
        LOGGER.info("[ARGUS] {} command executed by player {} with linked Discord account {} ({})",
            commandName, playerProfile.name, discordUser.username, discordUser.id)
        
        return true
    }
    
    /**
     * Get the Discord ID for a player or null if not linked
     * 
     * @param playerProfile The player's GameProfile
     * @return The Discord ID as a string, or null if not linked
     */
    @JvmStatic
    fun getDiscordIdForPlayer(playerProfile: GameProfile?): String? {
        if (playerProfile == null) {
            return null
        }
        
        val discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.id)
        return discordUser?.id
    }
}