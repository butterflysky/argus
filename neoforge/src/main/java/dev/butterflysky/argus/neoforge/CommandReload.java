package dev.butterflysky.argus.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import dev.butterflysky.argus.common.ArgusCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Implements /argus reload for NeoForge parity with Fabric. */
public class CommandReload {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("argus")
            .requires(stack -> stack.hasPermission(3))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ArgusCore.initializeJvm();
                    ArgusCore.startDiscordJvm();
                    ctx.getSource().sendSuccess(() -> Component.literal("Argus config reloaded"), false);
                    return 1;
                })
            )
        );
    }
}
