package dev.butterflysky.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.butterflysky.service.DiscordUserInfo;
import dev.butterflysky.service.WhitelistService;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.dedicated.command.BanCommand;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Mixin to intercept the Minecraft 'ban' command
 * 
 * This ensures that banned players are also removed from the whitelist
 * and their ban status is tracked in our database. This mixin only processes
 * bans issued directly through the Minecraft console or in-game commands,
 * not those issued through Discord (which use the WhitelistService directly).
 */
@Mixin(BanCommand.class)
public class BanCommandMixin {
    private static final Logger ARGUS_LOGGER = LoggerFactory.getLogger("argus");
    private static final WhitelistService WHITELIST_SERVICE = WhitelistService.Companion.getInstance();

    /**
     * Inject at the return of the ban method to ensure it runs after successful banning
     */
    @Inject(
        method = "ban(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/text/Text;)I", 
        at = @At("RETURN"),
        cancellable = false
    )
    private static void onPlayerBanned(ServerCommandSource source, Collection<GameProfile> targets, Text reason, CallbackInfoReturnable<Integer> cir) {
        // Only process if the ban was successful (return value > 0)
        if (cir.getReturnValue() <= 0) {
            return;
        }
        
        ARGUS_LOGGER.info("[ARGUS BAN] Minecraft ban command executed, updating whitelist status for banned players");
        
        // Get ban reason
        String banReason = reason != null ? reason.getString() : "Banned by an operator";
        
        // Check if command was executed by a player or the console
        GameProfile playerProfile = null;
        String discordId = null;
        
        // Check if the command was executed by a player
        if (source.getEntity() != null && source.isExecutedByPlayer()) {
            try {
                playerProfile = source.getPlayer().getGameProfile();
                
                // If we have a player, try to get their Discord ID
                if (playerProfile != null) {
                    DiscordUserInfo discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.getId());
                    if (discordUser != null) {
                        discordId = discordUser.getId();
                        ARGUS_LOGGER.info("[ARGUS BAN] Command executed by player {} with linked Discord account {} ({})",
                            playerProfile.getName(), discordUser.getUsername(), discordId);
                    } else {
                        ARGUS_LOGGER.info("[ARGUS BAN] Command executed by player {} with no linked Discord account, using system account",
                            playerProfile.getName());
                    }
                }
            } catch (Exception e) {
                ARGUS_LOGGER.warn("[ARGUS BAN] Error getting player information: {}", e.getMessage());
            }
        } else {
            // Command was executed from console or RCON
            ARGUS_LOGGER.info("[ARGUS BAN] Ban command executed from console or RCON, using system account");
        }

        // For each banned player, update their status in our system
        for (GameProfile profile : targets) {
            if (profile != null) {
                String playerName = profile.getName();
                ARGUS_LOGGER.info("[ARGUS BAN] Processing ban for player: {}", playerName);
                
                // Update player status in our system using the handler designed for vanilla commands
                boolean success = WHITELIST_SERVICE.handlePlayerBanned(
                    profile.getId(),
                    profile.getName(),
                    discordId,
                    banReason
                );
                
                if (success) {
                    ARGUS_LOGGER.info("[ARGUS BAN] Successfully processed ban for {}", playerName);
                } else {
                    ARGUS_LOGGER.warn("[ARGUS BAN] Failed to update ban status for {} in Argus database", playerName);
                }
            }
        }
    }
}