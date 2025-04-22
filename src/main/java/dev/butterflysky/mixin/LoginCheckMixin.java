package dev.butterflysky.mixin;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.service.WhitelistService;
import dev.butterflysky.whitelist.LinkManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.net.SocketAddress;

/**
 * Mixin to intercept login attempts and handle Discord linking requirements
 */
@Mixin(PlayerManager.class)
public class LoginCheckMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("argus-login");

    /**
     * Intercept the checkCanJoin method which is called during login.
     * This is where we'll handle our custom whitelist/linking logic.
     */
    @Inject(method = "checkCanJoin", at = @At("RETURN"), cancellable = true)
    private void onCheckCanJoin(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        // If vanilla Minecraft already rejected the login, don't interfere
        if (cir.getReturnValue() != null) {
            return;
        }

        try {
            // Get references to our services
            WhitelistService whitelistService = WhitelistService.Companion.getInstance();
            LinkManager linkManager = LinkManager.Companion.getInstance();

            // At this point, if we're here, the player is already approved by vanilla Minecraft's whitelist
            // Make sure we have a corresponding entry in our database
            if (!whitelistService.isWhitelisted(profile.getId())) {
                // Import this specific player as a legacy whitelist entry
                // This is called on-demand now, instead of as a bulk import on startup
                LOGGER.info("Player {} is in vanilla whitelist but not in our database. Creating legacy entry.", 
                    profile.getName());
                
                whitelistService.importPlayerFromVanillaWhitelist(profile);
            }

            // Now check if they have a Discord account linked
            boolean hasDiscordLink = whitelistService.getDiscordUserForMinecraftAccount(profile.getId()) != null;
            
            if (!hasDiscordLink) {
                // Player is whitelisted but doesn't have Discord linked, create a token
                String token = linkManager.createLinkToken(profile.getId(), profile.getName());
                
                // Create a formatted message with the token for the player
                Text message = Text.literal("")
                    .append(Text.literal("§c§lYour account needs to be linked with Discord to play\n\n"))
                    .append(Text.literal("§eJoin our Discord server and run the following command:\n"))
                    .append(Text.literal("§b/whitelist link " + token + "\n\n"))
                    .append(Text.literal("§7This token will expire in a few minutes"));
                
                LOGGER.info("Player {} attempted to join but needs to link Discord account. Token: {}", 
                    profile.getName(), token);
                
                // Return the message to disconnect them with instructions
                cir.setReturnValue(message);
            }
        } catch (Exception e) {
            LOGGER.error("Error in login check for {}: {}", profile.getName(), e.getMessage(), e);
        }
    }
}