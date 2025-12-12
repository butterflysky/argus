package dev.butterflysky.argus.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.ArgusConfig;
import dev.butterflysky.argus.common.LinkTokenService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

/** Implements NeoForge /argus admin commands (reload/config/token/tokens/help). */
public class CommandReload {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        final String prefix = "[argus] ";
        dispatcher.register(Commands.literal("argus")
            .requires(stack -> stack.hasPermission(3))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    ArgusCore.reloadConfigJvm();
                    ctx.getSource().sendSuccess(() -> Component.literal(prefix + "Argus config reloaded"), false);
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
                                ctx.getSource().sendSuccess(() -> Component.literal(prefix + field + " = " + value), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal(prefix + "Unknown field: " + field));
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
                                    ctx.getSource().sendSuccess(() -> Component.literal(prefix + "Set " + field + " = " + value), false);
                                    return 1;
                                } else {
                                    ctx.getSource().sendFailure(Component.literal(prefix + "Failed to update config field (see server log)"));
                                    return 0;
                                }
                            })
                        )
                    )
                )
            )
            .then(Commands.literal("tokens")
                .requires(stack -> stack.hasPermission(3))
                .executes(ctx -> listTokens(ctx, prefix))
            )
            .then(Commands.literal("token")
                .requires(stack -> stack.hasPermission(3))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(ctx -> issueToken(ctx, prefix))
                )
            )
            .then(Commands.literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(prefix + helpText()), false);
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
            "/argus config set <field> <value> - update argus.json (tab-complete fields)",
            "/argus token <player> - issue a link token for that player",
            "/argus tokens - list active link tokens",
            "Discord-side: /whitelist (add/remove/status/apply/list/approve/deny/warn/ban/unban/comment/review/my/help)"
        );
    }

    private static int listTokens(CommandContext<CommandSourceStack> ctx, String prefix) {
        var tokens = LinkTokenService.INSTANCE.listActive();
        if (tokens.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(prefix + "No active link tokens"), false);
            return 1;
        }
        tokens.forEach(t -> {
            var secs = t.getExpiresInMillis() / 1000;
            var namePart = t.getMcName() != null ? " mcName=" + t.getMcName() : "";
            ctx.getSource().sendSuccess(() ->
                Component.literal(prefix + "token=" + t.getToken() + " uuid=" + t.getUuid() + namePart + " expires_in=" + secs + "s"), false);
        });
        return 1;
    }

    private static int issueToken(CommandContext<CommandSourceStack> ctx, String prefix) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        var profile = profiles.stream().findFirst().orElse(null);
        if (profile == null) return 0;
        UUID uuid = profile.id();
        String name = profile.name();
        String token = LinkTokenService.INSTANCE.issueToken(uuid, name);
        ctx.getSource().sendSuccess(() -> Component.literal(prefix + "Link token for " + name + ": " + token), false);
        return 1;
    }
}
