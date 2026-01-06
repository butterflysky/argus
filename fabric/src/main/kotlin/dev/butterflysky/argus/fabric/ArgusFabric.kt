package dev.butterflysky.argus.fabric

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.butterflysky.argus.common.ArgusConfig
import dev.butterflysky.argus.common.ArgusCore
import dev.butterflysky.argus.common.LoginIntrospection
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.command.CommandSource
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

class ArgusFabric : ModInitializer {
    private val logger = LoggerFactory.getLogger("argus-fabric")
    private val prefix = "[argus] "
    private var currentServer: MinecraftServer? = null

    override fun onInitialize() {
        logger.info("Argus Fabric initializing (skeleton)")
        ArgusCore.initialize()
            .onFailure { logger.error("Failed to load Argus cache", it) }

        ArgusCore.startDiscord()
        ArgusCore.registerMessenger { uuid, message ->
            currentServer?.playerManager?.getPlayer(uuid)?.sendMessage(Text.literal(message), false)
        }
        ArgusCore.registerBanSync(
            ban = { uuid, name, reason, until ->
                currentServer?.playerManager?.let { LoginIntrospection.ban(it, uuid, name, reason, until) }
            },
            unban = { uuid ->
                currentServer?.playerManager?.let { LoginIntrospection.unban(it, uuid) }
            },
        )

        registerCommands()
        registerJoinGreeting()
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("argus")
                    .requires { PermissionBridge.hasPermissionLevel(it, 3) }
                    .then(
                        literal("reload")
                            .executes { ctx -> reloadConfig(ctx) },
                    )
                    .then(
                        literal("help")
                            .executes { ctx ->
                                ctx.source.sendFeedback({ Text.literal("$prefix${helpText()}") }, false)
                                1
                            },
                    )
                    .then(
                        literal("config")
                            .then(
                                literal("get")
                                    .then(
                                        net.minecraft.server.command.CommandManager.argument("field", StringArgumentType.word())
                                            .suggests(::suggestFields)
                                            .executes { ctx -> getConfig(ctx) },
                                    ),
                            )
                            .then(
                                literal("set")
                                    .then(
                                        net.minecraft.server.command.CommandManager.argument("field", StringArgumentType.word())
                                            .suggests(::suggestFields)
                                            .then(
                                                net.minecraft.server.command.CommandManager.argument(
                                                    "value",
                                                    StringArgumentType.greedyString(),
                                                )
                                                    .suggests(::suggestValue)
                                                    .executes { ctx -> setConfig(ctx) },
                                            ),
                                    ),
                            ),
                    )
                    .then(
                        literal("token")
                            .requires { PermissionBridge.hasPermissionLevel(it, 3) }
                            .then(
                                net.minecraft.server.command.CommandManager.argument(
                                    "player",
                                    net.minecraft.command.argument.GameProfileArgumentType.gameProfile(),
                                )
                                    .executes { ctx -> issueToken(ctx) },
                            ),
                    )
                    .then(
                        literal("tokens")
                            .requires { PermissionBridge.hasPermissionLevel(it, 3) }
                            .executes { ctx -> listTokens(ctx) },
                    ),
            )
        }
    }

    init {
        ServerLifecycleEvents.SERVER_STOPPING.register(
            ServerLifecycleEvents.ServerStopping { _ ->
                ArgusCore.stopDiscord()
            },
        )
    }

    private fun setConfig(ctx: CommandContext<ServerCommandSource>): Int {
        val field = StringArgumentType.getString(ctx, "field")
        val value = StringArgumentType.getString(ctx, "value")
        val result =
            ArgusConfig.update(field, value)
                .onSuccess {
                    ArgusCore.reloadConfigAsync().whenComplete { reload, err ->
                        ctx.source.server.execute {
                            when {
                                err != null ->
                                    ctx.source.sendError(
                                        Text.literal("${prefix}Argus reload failed: ${err.message}"),
                                    )
                                reload != null ->
                                    reload.onFailure {
                                        ctx.source.sendError(Text.literal("${prefix}Argus reload failed: ${it.message}"))
                                    }
                            }
                        }
                    }
                }
        return result.fold(
            onSuccess = {
                ctx.source.sendFeedback({ Text.literal("${prefix}Set $field = $value") }, false)
                1
            },
            onFailure = {
                ctx.source.sendError(Text.literal("${prefix}Failed: ${it.message}"))
                0
            },
        )
    }

    private fun getConfig(ctx: CommandContext<ServerCommandSource>): Int {
        val field = StringArgumentType.getString(ctx, "field")
        val result = ArgusConfig.get(field)
        return result.fold(
            onSuccess = {
                ctx.source.sendFeedback({ Text.literal("${prefix}$field = $it") }, false)
                1
            },
            onFailure = {
                ctx.source.sendError(Text.literal("${prefix}Unknown or invalid field: ${it.message}"))
                0
            },
        )
    }

    private fun reloadConfig(ctx: CommandContext<ServerCommandSource>): Int {
        ArgusCore.reloadConfigAsync().whenComplete { result, _ ->
            ctx.source.server.execute {
                result.fold(
                    onSuccess = {
                        ctx.source.sendFeedback({ Text.literal("${prefix}Argus config reloaded") }, false)
                    },
                    onFailure = {
                        ctx.source.sendError(Text.literal("${prefix}Argus reload failed: ${it.message}"))
                    },
                )
            }
        }
        return 1
    }

    private fun issueToken(ctx: CommandContext<ServerCommandSource>): Int {
        val profiles = net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(ctx, "player")
        val profile = profiles.firstOrNull() ?: return 0
        val token = dev.butterflysky.argus.common.LinkTokenService.issueToken(profile.id, profile.name)
        ctx.source.sendFeedback({ Text.literal("${prefix}Link token for ${profile.name}: $token") }, false)
        return 1
    }

    private fun listTokens(ctx: CommandContext<ServerCommandSource>): Int {
        val tokens = dev.butterflysky.argus.common.LinkTokenService.listActive()
        if (tokens.isEmpty()) {
            ctx.source.sendFeedback({ Text.literal("${prefix}No active link tokens") }, false)
            return 1
        }
        tokens.forEach {
            val secs = it.expiresInMillis / 1000
            ctx.source.sendFeedback(
                {
                    val namePart = it.mcName?.let { n -> " mcName=$n" } ?: ""
                    Text.literal("${prefix}token=${it.token} uuid=${it.uuid}$namePart expires_in=${secs}s")
                },
                false,
            )
        }
        return 1
    }

    private fun helpText(): String =
        """
        /argus reload - reload config and restart Discord (if configured)
        /argus config get <field> - show current value
        /argus config set <field> <value> - update argus.json (tab-complete fields)
        /argus token <player> - issue a link token for that player
        Discord-side: /whitelist (add/remove/status/apply/list/approve/deny/warn/ban/unban/comment/review/my/help)
        """.trimIndent()

    private fun suggestFields(
        ctx: CommandContext<ServerCommandSource>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder,
    ) = net.minecraft.command.CommandSource.suggestMatching(ArgusConfig.fieldNames(), builder)

    private fun suggestValue(
        ctx: CommandContext<ServerCommandSource>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder,
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val field = runCatching { StringArgumentType.getString(ctx, "field") }.getOrNull()
        field?.let { ArgusConfig.sampleValue(it)?.let { sample -> builder.suggest(sample) } }
        return builder.buildFuture()
    }

    private fun registerJoinGreeting() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val profile = player.gameProfile
            val isOp = PermissionBridge.hasPermissionLevel(player, 4)
            val whitelistEnabled = server.isEnforceWhitelist

            ArgusCore.onPlayerJoin(profile.id, isOp, whitelistEnabled, profile.name)?.let { message ->
                // If message is a kick, disconnect; otherwise send chat message.
                if (message.contains("Access revoked") || message.contains("Link required")) {
                    player.networkHandler.disconnect(Text.literal(message))
                } else {
                    player.sendMessage(clickableIfLink(message), false)
                }
            }
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            currentServer = server
            logger.info("Argus login guards registered on server {}", server.name)
            if (java.lang.Boolean.getBoolean("argus.smoke")) {
                logger.info("Argus Fabric smoke flag set; stopping server after startup")
                server.stop(false)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { currentServer = null }
    }

    private fun clickableIfLink(message: String): Text = Text.literal(message)
}

private object PermissionBridge {
    private val legacyMethodCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()

    fun hasPermissionLevel(
        source: Any,
        level: Int,
    ): Boolean {
        val legacy =
            legacyMethodCache.computeIfAbsent(source.javaClass) { clazz ->
                runCatching { clazz.getMethod("hasPermissionLevel", Int::class.javaPrimitiveType) }.getOrNull()
            }
        if (legacy != null) {
            return runCatching { legacy.invoke(source, level) as Boolean }.getOrDefault(false)
        }

        return runCatching {
            val getPermissions = source.javaClass.getMethod("getPermissions")
            val permissions = getPermissions.invoke(source)
            val permissionInterface = Class.forName("net.minecraft.command.permission.Permission")
            val permissionLevelClass = Class.forName("net.minecraft.command.permission.PermissionLevel")
            val fromLevel = permissionLevelClass.getMethod("fromLevel", Int::class.javaPrimitiveType)
            val levelEnum = fromLevel.invoke(null, level)
            val permissionLevelRecord = Class.forName("net.minecraft.command.permission.Permission\$Level")
            val ctor = permissionLevelRecord.getConstructor(permissionLevelClass)
            val permission = ctor.newInstance(levelEnum)
            val hasPermission = permissions.javaClass.getMethod("hasPermission", permissionInterface)
            hasPermission.invoke(permissions, permission) as Boolean
        }.getOrDefault(false)
    }
}
