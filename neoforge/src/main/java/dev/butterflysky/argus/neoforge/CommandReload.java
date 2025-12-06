package dev.butterflysky.argus.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.butterflysky.argus.common.ArgusConfig;
import dev.butterflysky.argus.common.ArgusCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Implements /argus reload and /argus config set for NeoForge. */
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
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(helpText()), false);
                    return 1;
                })
            )
            .then(Commands.literal("config")
                .then(Commands.literal("set")
                    .then(Commands.argument("field", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String field = StringArgumentType.getString(ctx, "field");
                                String value = StringArgumentType.getString(ctx, "value");
                                boolean ok = ArgusConfig.updateFromJava(field, value);
                                if (ok) {
                                    ArgusCore.reloadConfigJvm();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Set " + field), false);
                                    return 1;
                                } else {
                                    ctx.getSource().sendFailure(Component.literal("Failed to update config field (see server log)"));
                                    return 0;
                                }
                            })
                        )
                    )
                )
            )
        );
    }

    private static String helpText() {
        return String.join("\n",
            "/argus reload - reload config and restart Discord (if configured)",
            "/argus help - this help",
            "/argus config set <field> <value> - update argus.json",
            "Discord-side: /whitelist (add/remove/status/apply/list/approve/deny/warn/ban/unban/comment/review/my/help)"
        );
    }
}
