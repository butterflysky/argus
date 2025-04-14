package dev.butterflysky.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public class WhitelistCommandExecuteMixin {
    private static final Logger ARGUS_LOGGER = LoggerFactory.getLogger("argus");

    @Inject(method = "execute", at = @At("HEAD"))
    private void onCommandExecute(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        String lowerCommand = command.toLowerCase().trim();
        
        if (lowerCommand.startsWith("whitelist ") || lowerCommand.equals("whitelist")) {
            ARGUS_LOGGER.info("[ARGUS WHITELIST] Whitelist command executed: {}", command);
            
            // Get the arguments part
            String[] args = command.split("\\s+");
            if (args.length >= 2) {
                String subCommand = args[1]; // add, remove, list, etc.
                ARGUS_LOGGER.info("[ARGUS WHITELIST] Subcommand: {}", subCommand);
                
                // For add/remove commands, log the player name
                if ((subCommand.equals("add") || subCommand.equals("remove")) && args.length >= 3) {
                    String playerName = args[2];
                    ARGUS_LOGGER.info("[ARGUS WHITELIST] Player name: {}", playerName);
                }
            }
        }
    }
}