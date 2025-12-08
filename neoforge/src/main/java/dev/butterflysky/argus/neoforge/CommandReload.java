package dev.butterflysky.argus.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.ArgusConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import java.util.concurrent.CompletableFuture;

/** Implements /argus reload and /argus config set for NeoForge. */
public class CommandReload {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("argus")
            .requires(stack -> stack.hasPermission(3))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ArgusCore.reloadConfigJvm();
                    ctx.getSource().sendSuccess(() -> Component.literal("Argus config reloaded"), false);
                    return 1;
                })
            )
            .then(Commands.literal("config")
                .then(Commands.literal("get")
                    .then(Commands.argument("field", StringArgumentType.word())
                        .suggests(CommandReload::suggestFields)
                        .executes(ctx -> {
                            String field = StringArgumentType.getString(ctx, "field");
                            String value = ArgusConfig.getValue(field);
                            if (value != null) {
                                ctx.getSource().sendSuccess(() -> Component.literal(field + " = " + value), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("Unknown field: " + field));
                            return 0;
                        })
                    )
                )
                .then(Commands.literal("set")
                    .then(Commands.argument("field", StringArgumentType.word())
                        .suggests(CommandReload::suggestFields)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                            .suggests(CommandReload::suggestValue)
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
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(helpText()), false);
                    return 1;
                })
            )
        );
    }

    private static CompletableFuture<Suggestions> suggestFields(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ArgusConfig.fieldNamesJvm(), builder);
    }

    private static CompletableFuture<Suggestions> suggestValue(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String field = ctx.getArgument("field", String.class);
        String sample = ArgusConfig.sampleValueJvm(field);
        if (sample != null) builder.suggest(sample);
        return builder.buildFuture();
    }

    private static String helpText() {
        return String.join("\n",
            "/argus reload - reload config and restart Discord (if configured)",
            "/argus config get <field> - show current value",
            "/argus help - this help",
            "/argus config set <field> <value> - update argus.json",
            "Discord-side: /whitelist (add/remove/status/apply/list/approve/deny/warn/ban/unban/comment/review/my/help)"
        );
    }
}
