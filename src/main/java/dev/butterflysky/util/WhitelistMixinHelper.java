package dev.butterflysky.util;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.service.DiscordUserInfo;
import dev.butterflysky.service.WhitelistService;
import dev.butterflysky.whitelist.LinkManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for common whitelist and command functionality
 */
public class WhitelistMixinHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("argus-mixins");
    private static final WhitelistService WHITELIST_SERVICE = WhitelistService.Companion.getInstance();
    private static final LinkManager LINK_MANAGER = LinkManager.Companion.getInstance();
    
    /**
     * Check if a player has their Minecraft account linked to Discord
     * If not, send them a message with a token for linking.
     * 
     * @param source The command source
     * @param commandName Name of the command being executed, for logging
     * @return true if account is linked or source is not a player (e.g., console), false otherwise
     */
    public static boolean checkDiscordLinkOrShowMessage(ServerCommandSource source, String commandName) {
        GameProfile playerProfile = source.getPlayer() != null ? source.getPlayer().getGameProfile() : null;
        
        // Allow console/RCON to execute regardless
        if (playerProfile == null) {
            LOGGER.info("[ARGUS] {} command executed from console or RCON", commandName);
            return true;
        }
        
        // Check if player has a linked Discord account
        DiscordUserInfo discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.getId());
        
        if (discordUser == null) {
            // Generate a token for linking
            String token = LINK_MANAGER.createLinkToken(playerProfile.getId(), playerProfile.getName());
            
            // Send a message to the player explaining they need to link their Discord account
            Text linkText = Text.literal("§c[Argus] §eYou need to link your Discord account to use " + commandName + " commands.\n")
                .append(Text.literal("§ePlease run §b/whitelist link §eto generate a token, then use that token in Discord.\n"))
                .append(Text.literal("§7(" + commandName + " commands are restricted to users with linked Discord accounts)"));
            
            source.sendFeedback(() -> linkText, false);
            
            LOGGER.info("[ARGUS] Player {} attempted to use {} command without linked Discord account", 
                playerProfile.getName(), commandName);
            
            return false;
        }
        
        LOGGER.info("[ARGUS] {} command executed by player {} with linked Discord account {} ({})",
            commandName, playerProfile.getName(), discordUser.getUsername(), discordUser.getId());
        
        return true;
    }
    
    /**
     * Get the Discord ID for a player or null if not linked
     * 
     * @param playerProfile The player's GameProfile
     * @return The Discord ID as a string, or null if not linked
     */
    public static String getDiscordIdForPlayer(GameProfile playerProfile) {
        if (playerProfile == null) {
            return null;
        }
        
        DiscordUserInfo discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.getId());
        if (discordUser != null) {
            return discordUser.getId();
        }
        
        return null;
    }
}