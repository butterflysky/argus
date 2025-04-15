package dev.butterflysky.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import dev.butterflysky.db.WhitelistDatabase;
import dev.butterflysky.service.DiscordUserInfo;
import dev.butterflysky.service.WhitelistService;
import dev.butterflysky.whitelist.LinkManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(CommandManager.class)
public class WhitelistCommandExecuteMixin {
    private static final Logger ARGUS_LOGGER = LoggerFactory.getLogger("argus");
    private static final WhitelistService WHITELIST_SERVICE = WhitelistService.Companion.getInstance();
    private static final LinkManager LINK_MANAGER = LinkManager.Companion.getInstance();

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void onCommandExecute(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        String lowerCommand = command.toLowerCase().trim();
        
        if (lowerCommand.startsWith("whitelist ") || lowerCommand.equals("whitelist")) {
            ServerCommandSource source = parseResults.getContext().getSource();
            GameProfile playerProfile = source.getPlayer() != null ? source.getPlayer().getGameProfile() : null;
            
            ARGUS_LOGGER.info("[ARGUS WHITELIST] Whitelist command executed: {}", command);
            
            // Get the arguments part
            String[] args = command.split("\\s+");
            if (args.length >= 2) {
                String subCommand = args[1]; // add, remove, list, etc.
                ARGUS_LOGGER.info("[ARGUS WHITELIST] Subcommand: {}", subCommand);
                
                // For commands that mutate whitelist state, check for Discord mapping
                if (isMutatingCommand(subCommand)) {
                    // For in-game players, require Discord link
                    if (playerProfile != null) {
                        DiscordUserInfo discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.getId());
                        
                        if (discordUser == null) {
                            // Block the command and provide a token for linking
                            String token = LINK_MANAGER.createLinkToken(playerProfile.getId(), playerProfile.getName());
                            
                            // Send a message to the player explaining they need to link their Discord account
                            Text linkText = Text.literal("§c[Argus] §eYou need to link your Discord account to use whitelist commands.\n")
                                .append(Text.literal("§ePlease run §b/whitelist link §eto generate a token, then use that token in Discord.\n"))
                                .append(Text.literal("§7(Whitelist commands are restricted to users with linked Discord accounts)"));
                            
                            source.sendFeedback(() -> linkText, false);
                            
                            // Cancel the command execution
                            ci.cancel();
                            return;
                        } else {
                            // Discord account found, log the action with the Discord user
                            ARGUS_LOGGER.info("[ARGUS WHITELIST] Command executed by player {} with linked Discord account {} ({})",
                                playerProfile.getName(), discordUser.getUsername(), discordUser.getId());
                        }
                    } else {
                        // Command issued by console/RCON, use the system account
                        ARGUS_LOGGER.info("[ARGUS WHITELIST] Command executed from console or RCON, using system account");
                    }
                    
                    // For add/remove commands, log the player name
                    if ((subCommand.equals("add") || subCommand.equals("remove")) && args.length >= 3) {
                        String targetPlayerName = args[2];
                        ARGUS_LOGGER.info("[ARGUS WHITELIST] Target player name: {}", targetPlayerName);
                    }
                }
            }
        }
    }
    
    private boolean isMutatingCommand(String subCommand) {
        return subCommand.equals("add") || 
               subCommand.equals("remove") || 
               subCommand.equals("on") || 
               subCommand.equals("off") || 
               subCommand.equals("reload");
    }
}