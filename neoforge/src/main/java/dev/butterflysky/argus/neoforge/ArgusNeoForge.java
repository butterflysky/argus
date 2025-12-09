package dev.butterflysky.argus.neoforge;

import dev.butterflysky.argus.common.ArgusCore;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge entrypoint wiring Argus shared logic. */
@Mod(ArgusNeoForge.MOD_ID)
public class ArgusNeoForge {
    public static final String MOD_ID = "argus";
    private static final Logger LOGGER = LoggerFactory.getLogger("argus-neoforge");
    private net.minecraft.server.MinecraftServer serverRef;

    public ArgusNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ArgusCore.initializeJvm();
        ArgusCore.startDiscordJvm();
        ArgusCore.INSTANCE.registerMessenger((uuid, message) -> {
            if (serverRef != null) {
                var player = serverRef.getPlayerList().getPlayer(uuid);
                if (player != null) player.sendSystemMessage(Component.literal(message));
            }
            return kotlin.Unit.INSTANCE;
        });
        ArgusCore.INSTANCE.registerBanSync(
                (uuid, name, reason, until) -> {
                    if (serverRef != null) {
                        dev.butterflysky.argus.common.LoginIntrospection.ban(serverRef.getPlayerList(), uuid, name, reason, until);
                    }
                    return kotlin.Unit.INSTANCE;
                },
                uuid -> {
                    if (serverRef != null) {
                        dev.butterflysky.argus.common.LoginIntrospection.unban(serverRef.getPlayerList(), uuid);
                    }
                    return kotlin.Unit.INSTANCE;
                }
        );
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandReload.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        var player = (net.minecraft.server.level.ServerPlayer) event.getEntity();
        var server = player.level().getServer();
        boolean whitelistEnabled = server != null && server.isEnforceWhitelist();
        String message = ArgusCore.INSTANCE.onPlayerJoin(player.getUUID(), player.hasPermissions(4), whitelistEnabled);
        if (message != null) {
            if (message.contains("Access revoked") || message.contains("Link required")) {
                player.connection.disconnect(Component.literal(message));
            } else {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        serverRef = event.getServer();
        LOGGER.info("Argus NeoForge hooks registered on server start");
        if (Boolean.getBoolean("argus.smoke")) {
            LOGGER.info("Argus NeoForge smoke flag set; stopping server after startup");
            serverRef.halt(false);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ArgusCore.INSTANCE.stopDiscord();
    }
}
