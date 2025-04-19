package dev.butterflysky.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import dev.butterflysky.db.WhitelistDatabase;
import dev.butterflysky.service.DiscordUserInfo;
import dev.butterflysky.service.WhitelistService;
import dev.butterflysky.service.WhitelistService.AddResult;
import dev.butterflysky.service.WhitelistService.RemoveResult;
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
                    
                    // For add/remove commands, handle the audit logging and Discord notifications
                    if ((subCommand.equals("add") || subCommand.equals("remove")) && args.length >= 3) {
                        String targetPlayerName = args[2];
                        ARGUS_LOGGER.info("[ARGUS WHITELIST] Target player name: {}", targetPlayerName);
                        
                        // Get the player profile from the server if available
                        GameProfile targetProfile = source.getServer().getUserCache().findByName(targetPlayerName).orElse(null);
                        
                        if (targetProfile != null) {
                            ARGUS_LOGGER.info("[ARGUS WHITELIST] Found profile for target player: {} ({})", 
                                targetProfile.getName(), targetProfile.getId());
                            
                            // Determine the discord ID of the player executing the command (if any)
                            String executorDiscordId = null;
                            if (playerProfile != null) {
                                DiscordUserInfo discordUser = WHITELIST_SERVICE.getDiscordUserForMinecraftAccount(playerProfile.getId());
                                if (discordUser != null) {
                                    executorDiscordId = discordUser.getId();
                                }
                            }
                            
                            boolean successful = false;
                            try {
                                // For whitelist add
                                if (subCommand.equals("add")) {
                                    // Explicitly call our service to add to whitelist to ensure audit logs are created
                                    WhitelistService.AddResult result = WHITELIST_SERVICE.addToWhitelist(
                                        targetProfile.getId(),
                                        targetProfile.getName(),
                                        executorDiscordId != null ? executorDiscordId : String.valueOf(WhitelistDatabase.SYSTEM_USER_ID),
                                        true,
                                        "Added via vanilla whitelist command"
                                    );
                                    ARGUS_LOGGER.info("[ARGUS WHITELIST] Add result: {}", result);
                                    
                                    if (result instanceof WhitelistService.AddResult.Success || 
                                        result instanceof WhitelistService.AddResult.AlreadyWhitelisted) {
                                        // Send a success message to match vanilla behavior
                                        String message = result instanceof WhitelistService.AddResult.Success ? 
                                            "Added " + targetPlayerName + " to the whitelist" :
                                            targetPlayerName + " is already whitelisted";
                                            
                                        source.sendFeedback(() -> Text.literal(message), true);
                                        successful = true;
                                    } else if (result instanceof WhitelistService.AddResult.Error) {
                                        WhitelistService.AddResult.Error error = (WhitelistService.AddResult.Error) result;
                                        source.sendFeedback(() -> Text.literal("§cError: " + error.getErrorMessage()), true);
                                    }
                                }
                                // For whitelist remove
                                else if (subCommand.equals("remove")) {
                                    // Explicitly call our service to remove from whitelist
                                    WhitelistService.RemoveResult result = WHITELIST_SERVICE.removeFromWhitelist(
                                        targetProfile.getId(),
                                        executorDiscordId != null ? executorDiscordId : String.valueOf(WhitelistDatabase.SYSTEM_USER_ID)
                                    );
                                    ARGUS_LOGGER.info("[ARGUS WHITELIST] Remove result: {}", result);
                                    
                                    if (result instanceof WhitelistService.RemoveResult.Success) {
                                        // Send a success message to match vanilla behavior
                                        source.sendFeedback(() -> Text.literal("Removed " + targetPlayerName + " from the whitelist"), true);
                                        successful = true;
                                    } else if (result instanceof WhitelistService.RemoveResult.NotWhitelisted) {
                                        source.sendFeedback(() -> Text.literal(targetPlayerName + " is not whitelisted"), true);
                                        successful = true; // Still cancel vanilla command as it would show the same message
                                    } else if (result instanceof WhitelistService.RemoveResult.OperatorProtected) {
                                        source.sendFeedback(() -> Text.literal("§cCannot remove " + targetPlayerName + " because they are a server operator"), true);
                                        successful = true; // Cancel vanilla command as we've handled this case
                                    } else if (result instanceof WhitelistService.RemoveResult.Error) {
                                        WhitelistService.RemoveResult.Error error = (WhitelistService.RemoveResult.Error) result;
                                        source.sendFeedback(() -> Text.literal("§cError: " + error.getErrorMessage()), true);
                                    }
                                }
                                
                                // If we successfully processed this command, cancel vanilla execution
                                if (successful) {
                                    ci.cancel();
                                    return;
                                }
                            } catch (Exception e) {
                                ARGUS_LOGGER.error("[ARGUS WHITELIST] Error handling whitelist command: {}", e.getMessage(), e);
                            }
                        } else {
                            ARGUS_LOGGER.warn("[ARGUS WHITELIST] Could not find profile for target player: {}", targetPlayerName);
                            // Let vanilla handle this case - it will show an appropriate error
                        }
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