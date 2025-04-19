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
     * Inject before the ban method to check for Discord link
     */
    @Inject(
        method = "ban(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/text/Text;)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void beforeBan(ServerCommandSource source, Collection<GameProfile> targets, Text reason, CallbackInfoReturnable<Integer> cir) {
        ARGUS_LOGGER.info("[ARGUS BAN] Minecraft ban command executed");
        
        // Check if command executor has a linked Discord account
        if (!MixinHelper.checkDiscordLinkOrShowMessage(source, "ban")) {
            // Cancel the command if no Discord link
            cir.setReturnValue(0);
            cir.cancel();
        }
    }

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
        
        // Get the Discord ID of the command executor (if any)
        GameProfile playerProfile = null;
        String discordId = null;
        
        try {
            if (source.getEntity() != null && source.isExecutedByPlayer()) {
                playerProfile = source.getPlayer().getGameProfile();
                discordId = MixinHelper.getDiscordIdForPlayer(playerProfile);
            }
        } catch (Exception e) {
            ARGUS_LOGGER.warn("[ARGUS BAN] Error getting player information: {}", e.getMessage());
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