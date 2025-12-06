package dev.butterflysky.argus.fabric

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.butterflysky.argus.common.ArgusConfig
import dev.butterflysky.argus.common.ArgusCore
import dev.butterflysky.argus.common.LoginResult
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.command.CommandSource
import net.minecraft.command.suggestion.SuggestionProviders
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerLoginNetworkHandler
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.util.UUID

class ArgusFabric : ModInitializer {
    private val logger = LoggerFactory.getLogger("argus-fabric")

    override fun onInitialize() {
        logger.info("Argus Fabric initializing (skeleton)")
        ArgusCore.initialize()
            .onFailure { logger.error("Failed to load Argus cache", it) }

        ArgusCore.startDiscord()

        registerCommands()
        registerLoginGuard()
        registerJoinGreeting()
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("argus")
                    .requires { it.hasPermissionLevel(3) }
                    .then(
                        literal("reload")
                            .executes { ctx -> reloadConfig(ctx) }
                    )
                    .then(
                        literal("help")
                            .executes { ctx ->
                                ctx.source.sendFeedback({ Text.literal(helpText()) }, false)
                                1
                            }
                    )
                    .then(
                        literal("config")
                            .then(
                                literal("get")
                                    .then(
                                        net.minecraft.server.command.CommandManager.argument("field", StringArgumentType.word())
                                            .suggests(::suggestFields)
                                            .executes { ctx -> getConfig(ctx) }
                                    )
                            )
                            .then(
                                literal("set")
                                    .then(
                                        net.minecraft.server.command.CommandManager.argument("field", StringArgumentType.word())
                                            .suggests(::suggestFields)
                                            .then(
                                                net.minecraft.server.command.CommandManager.argument("value", StringArgumentType.greedyString())
                                                    .suggests(::suggestValue)
                                                    .executes { ctx -> setConfig(ctx) }
                                            )
                                    )
                            )
                    )
                    .then(
                        literal("token")
                            .requires { it.hasPermissionLevel(3) }
                            .then(
                                net.minecraft.server.command.CommandManager.argument("player", net.minecraft.command.argument.GameProfileArgumentType.gameProfile())
                                    .executes { ctx -> issueToken(ctx) }
                            )
                    )
            )
        }
    }

    private fun setConfig(ctx: CommandContext<ServerCommandSource>): Int {
        val field = StringArgumentType.getString(ctx, "field")
        val value = StringArgumentType.getString(ctx, "value")
        val result = ArgusConfig.update(field, value)
            .onSuccess { ArgusCore.reloadConfig() }
        return result.fold(
            onSuccess = {
                ctx.source.sendFeedback({ Text.literal("Set $field") }, false); 1
            },
            onFailure = {
                ctx.source.sendError(Text.literal("Failed: ${it.message}")); 0
            }
        )
    }

    private fun getConfig(ctx: CommandContext<ServerCommandSource>): Int {
        val field = StringArgumentType.getString(ctx, "field")
        val result = ArgusConfig.get(field)
        return result.fold(
            onSuccess = {
                ctx.source.sendFeedback({ Text.literal("$field = $it") }, false); 1
            },
            onFailure = {
                ctx.source.sendError(Text.literal("Unknown or invalid field: ${it.message}")); 0
            }
        )
    }

    private fun reloadConfig(ctx: CommandContext<ServerCommandSource>): Int {
        val result = ArgusCore.initialize()
        return result.fold(
            onSuccess = {
                ctx.source.sendFeedback({ Text.literal("Argus config reloaded") }, false)
                1
            },
            onFailure = {
                ctx.source.sendError(Text.literal("Argus reload failed: ${it.message}"))
                0
            }
        )
    }

    private fun issueToken(ctx: CommandContext<ServerCommandSource>): Int {
        val profiles = net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(ctx, "player")
        val profile = profiles.firstOrNull() ?: return 0
        val token = dev.butterflysky.argus.common.LinkTokenService.issueToken(profile.id, profile.name)
        ctx.source.sendFeedback({ Text.literal("Argus link token for ${profile.name}: $token") }, false)
        return 1
    }

    private fun helpText(): String = """
        /argus reload - reload config and restart Discord (if configured)
        /argus config get <field> - show current value
        /argus config set <field> <value> - update argus.json (tab-complete fields)
        /argus token <player> - issue a link token for that player
        Discord-side: /whitelist (add/remove/status/apply/list/approve/deny/warn/ban/unban/comment/review/my/help)
    """.trimIndent()

    private fun suggestFields(ctx: CommandContext<ServerCommandSource>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        net.minecraft.command.CommandSource.suggestMatching(ArgusConfig.fieldNames(), builder)

    private fun suggestValue(ctx: CommandContext<ServerCommandSource>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val field = runCatching { StringArgumentType.getString(ctx, "field") }.getOrNull()
        field?.let { ArgusConfig.sampleValue(it)?.let { sample -> builder.suggest(sample) } }
        return builder.buildFuture()
    }

    private fun registerLoginGuard() {
        ServerLoginConnectionEvents.QUERY_START.register { handler, server, _, _ ->
            val profile = extractProfile(handler) ?: return@register
            val uuid: UUID = profile.id
            val name = profile.name
            val isOp = reflectBool(server.playerManager, "isOperator", profile)
            val isWhitelisted = reflectBool(server.playerManager, "isWhitelisted", profile)
            val whitelistEnabled = reflectServerBool(server, listOf("isEnforceWhitelist"))

            when (val result = ArgusCore.onPlayerLogin(uuid, name, isOp, isWhitelisted, whitelistEnabled)) {
                LoginResult.Allow -> Unit
                is LoginResult.AllowWithKick -> handler.disconnect(Text.literal(result.message))
                is LoginResult.Deny -> handler.disconnect(Text.literal(result.message))
            }
        }
    }

    private fun registerJoinGreeting() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val profile = player.gameProfile
            val isOp = player.hasPermissionLevel(4)
            val whitelistEnabled = reflectServerBool(server, listOf("isEnforceWhitelist"))

            ArgusCore.onPlayerJoin(profile.id, isOp, whitelistEnabled)?.let {
                player.sendMessage(Text.literal(it), false)
            }
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            logger.info("Argus login guards registered on server {}", server.name)
        }
    }

    private fun extractProfile(handler: ServerLoginNetworkHandler): com.mojang.authlib.GameProfile? {
        val candidates = listOf("profile", "gameProfile", "field_14168", "field_14346")
        for (name in candidates) {
            runCatching {
                val field = handler.javaClass.getDeclaredField(name)
                field.isAccessible = true
                val value = field.get(handler)
                if (value is com.mojang.authlib.GameProfile) return value
            }
        }
        return runCatching {
            val method = handler.javaClass.methods.firstOrNull { it.name in listOf("getProfile", "getGameProfile") && it.parameterCount == 0 }
            method?.invoke(handler) as? com.mojang.authlib.GameProfile
        }.getOrNull()
    }

    private fun reflectBool(target: Any, methodName: String, arg: Any): Boolean {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1
            } ?: return@runCatching false
            method.isAccessible = true
            (method.invoke(target, arg) as? Boolean) ?: false
        }.getOrDefault(false)
    }

    private fun reflectServerBool(server: MinecraftServer, names: List<String>): Boolean {
        return names.firstNotNullOfOrNull { name ->
            runCatching {
                val method = server.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                method?.isAccessible = true
                method?.invoke(server) as? Boolean
            }.getOrNull()
        } ?: false
    }
}
