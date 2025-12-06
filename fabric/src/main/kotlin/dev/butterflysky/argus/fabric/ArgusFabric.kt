package dev.butterflysky.argus.fabric

import com.mojang.brigadier.context.CommandContext
import dev.butterflysky.argus.common.ArgusCore
import dev.butterflysky.argus.common.LoginResult
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
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

    private fun registerLoginGuard() {
        ServerLoginConnectionEvents.QUERY_START.register { handler, server, _, _ ->
            val profile = extractProfile(handler) ?: return@register
            val uuid: UUID = profile.id
            val name = profile.name
            val isOp = reflectBool(server.playerManager, "isOperator", profile)
            val isWhitelisted = reflectBool(server.playerManager, "isWhitelisted", profile)

            when (val result = ArgusCore.onPlayerLogin(uuid, name, isOp, isWhitelisted)) {
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

            ArgusCore.onPlayerJoin(profile.id, isOp)?.let {
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
}
