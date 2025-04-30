package dev.butterflysky.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.butterflysky.db.WhitelistDatabase;
import dev.butterflysky.service.WhitelistService;
import dev.butterflysky.util.MixinHelpers;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.dedicated.command.PardonCommand;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.UUID;

/**
 * Mixin to intercept the Minecraft 'pardon' command
 * 
 * This ensures that player pardons (unbans) are logged to our audit system and
 * announced to Discord. Similar to the BanCommandMixin, this only processes pardons
 * issued through Minecraft commands, not those issued through Discord.
 */
@Mixin(PardonCommand.class)
public class PardonCommandMixin {
    private static final Logger ARGUS_LOGGER = LoggerFactory.getLogger("argus");
    private static final WhitelistService WHITELIST_SERVICE = WhitelistService.Companion.getInstance();
    
    /**
     * Inject before the pardon method to check for Discord link
     */
    @Inject(
        method = "pardon(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void beforePardon(ServerCommandSource source, Collection<GameProfile> targets, CallbackInfoReturnable<Integer> cir) {
        ARGUS_LOGGER.info("[ARGUS PARDON] Minecraft pardon command executed");
        
        // Check if command executor has a linked Discord account
        if (!MixinHelpers.checkDiscordLinkOrShowMessage(source, "pardon")) {
            // Cancel the command if no Discord link
            cir.setReturnValue(0);
            cir.cancel();
        }
    }
    
    /**
     * Inject at the return of the pardon method to create audit logs
     */
    @Inject(
        method = "pardon(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;)I", 
        at = @At("RETURN"),
        cancellable = false
    )
    private static void onPlayerPardoned(ServerCommandSource source, Collection<GameProfile> targets, CallbackInfoReturnable<Integer> cir) {
        // Only process if the pardon was successful (return value > 0)
        if (cir.getReturnValue() <= 0) {
            return;
        }
        
        ARGUS_LOGGER.info("[ARGUS PARDON] Processing pardons for {} players", targets.size());
        
        // Get command executor's Discord ID
        GameProfile playerProfile = null;
        String discordId = null;
        
        // Check if the command was executed by a player
        if (source.getEntity() != null && source.isExecutedByPlayer()) {
            try {
                playerProfile = source.getPlayer().getGameProfile();
                discordId = MixinHelpers.getDiscordIdForPlayer(playerProfile);
            } catch (Exception e) {
                ARGUS_LOGGER.warn("[ARGUS PARDON] Error getting player information: {}", e.getMessage());
            }
        }
        
        // For each pardoned player, create an audit log
        for (GameProfile profile : targets) {
            if (profile != null) {
                String playerName = profile.getName();
                UUID uuid = profile.getId();
                ARGUS_LOGGER.info("[ARGUS PARDON] Processing pardon for player: {}", playerName);
                
                try {
                    // Get the performer's Discord user or system user if needed
                    final String discordIdFinal = discordId;
                    
                    // Create the audit log using our Kotlin transaction function
                    WHITELIST_SERVICE.createUnbanAuditLogEntry(
                        uuid,
                        playerName,
                        discordIdFinal,
                        "Pardoned via Minecraft command"
                    );
                    
                    ARGUS_LOGGER.info("[ARGUS PARDON] Created audit log for pardon of {}", playerName);
                } catch (Exception e) {
                    ARGUS_LOGGER.error("[ARGUS PARDON] Error creating audit log for pardon of {}: {}", playerName, e.getMessage(), e);
                }
            }
        }
    }
}