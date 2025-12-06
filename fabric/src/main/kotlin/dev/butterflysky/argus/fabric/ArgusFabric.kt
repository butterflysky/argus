package dev.butterflysky.argus.fabric

import com.mojang.brigadier.context.CommandContext
import dev.butterflysky.argus.common.ArgusCore
import dev.butterflysky.argus.common.LoginResult
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

class ArgusFabric : ModInitializer {
    private val logger = LoggerFactory.getLogger("argus-fabric")

    override fun onInitialize() {
        logger.info("Argus Fabric initializing (skeleton)")
        ArgusCore.initialize()
            .onFailure { logger.error("Failed to load Argus cache", it) }

        registerCommands()
        registerJoinGuard()
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
            )
        }
    }

    private fun reloadConfig(ctx: CommandContext<ServerCommandSource>): Int {
        // Placeholder: configuration loading will be implemented with the new schema.
        ctx.source.sendFeedback({ Text.literal("Argus config reload not yet implemented") }, false)
        return 1
    }

    private fun registerJoinGuard() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val profile = player.gameProfile
            val isOp = server.playerManager.isOperator(profile)
            val isWhitelisted = server.playerManager.isWhitelisted(profile)

            when (val result = ArgusCore.onPlayerLogin(profile.id, profile.name, isOp, isWhitelisted)) {
                LoginResult.Allow -> {
                    ArgusCore.onPlayerJoin(profile.id, isOp)?.let {
                        player.sendMessage(Text.literal(it), false)
                    }
                }
                is LoginResult.AllowWithKick -> player.networkHandler.disconnect(Text.literal(result.message))
                is LoginResult.Deny -> player.networkHandler.disconnect(Text.literal(result.message))
            }
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            logger.info("Argus login guards registered on server {}", server.name)
        }
    }
}
