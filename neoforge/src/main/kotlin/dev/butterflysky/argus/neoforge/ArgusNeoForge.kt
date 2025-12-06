package dev.butterflysky.argus.neoforge

import dev.butterflysky.argus.common.ArgusCore
import dev.butterflysky.argus.common.LoginResult
import net.minecraft.network.chat.Component
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.eventbus.api.SubscribeEvent
import net.neoforged.neoforge.fml.common.Mod.EventBusSubscriber
import net.neoforged.neoforge.fml.common.Mod.EventBusSubscriber.Bus
import org.slf4j.LoggerFactory

@Mod(ArgusNeoForge.MOD_ID)
@EventBusSubscriber(modid = ArgusNeoForge.MOD_ID, bus = Bus.FORGE)
class ArgusNeoForge {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener(this::commonSetup)
    }

    private fun commonSetup(event: FMLCommonSetupEvent) {
        ArgusCore.initialize().onFailure {
            logger.error("Failed to load Argus cache", it)
        }
        ArgusCore.startDiscord()
    }

    companion object {
        const val MOD_ID = "argus"
        private val logger = LoggerFactory.getLogger("argus-neoforge")

        @SubscribeEvent
        @JvmStatic
        fun onPlayerLogin(event: PlayerEvent.PlayerLoggedInEvent) {
            val player = event.entity
            val isOp = player.hasPermissions(4)
            val isWhitelisted = event.entity.server?.playerList?.isWhiteListed(player.gameProfile) ?: false
            when (val result = ArgusCore.onPlayerLogin(player.uuid, player.scoreboardName, isOp, isWhitelisted)) {
                LoginResult.Allow -> Unit
                is LoginResult.AllowWithKick -> player.connection.disconnect(Component.literal(result.message))
                is LoginResult.Deny -> player.connection.disconnect(Component.literal(result.message))
            }
        }

        @SubscribeEvent
        @JvmStatic
        fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
            val player = event.entity
            ArgusCore.onPlayerJoin(player.uuid, player.hasPermissions(4))?.let {
                player.sendSystemMessage(Component.literal(it))
            }
        }

        @SubscribeEvent
        @JvmStatic
        fun onServerStarted(event: ServerStartedEvent) {
            logger.info("Argus NeoForge hooks registered on server start")
        }
    }
}
