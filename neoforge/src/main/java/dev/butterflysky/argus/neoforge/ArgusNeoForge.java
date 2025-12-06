package dev.butterflysky.argus.neoforge;

import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.LoginResult;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
        modEventBus.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.register(this);
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
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandReload.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var player = (net.minecraft.server.level.ServerPlayer) event.getEntity();
        var server = player.level().getServer();
        boolean isOp = player.hasPermissions(4);
        var nameAndId = player.nameAndId();
        boolean isWhitelisted = server != null && server.getPlayerList().isWhiteListed(nameAndId);
        boolean whitelistEnabled = server != null && server.isEnforceWhitelist();

        LoginResult result = ArgusCore.INSTANCE.onPlayerLogin(player.getUUID(), player.getScoreboardName(), isOp, isWhitelisted, whitelistEnabled);
        if (result instanceof LoginResult.Allow) return;
        if (result instanceof LoginResult.AllowWithKick allow) {
            player.connection.disconnect(Component.literal(allow.getMessage()));
        } else if (result instanceof LoginResult.Deny deny) {
            player.connection.disconnect(Component.literal(deny.getMessage()));
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        var player = (net.minecraft.server.level.ServerPlayer) event.getEntity();
        var server = player.level().getServer();
        boolean whitelistEnabled = server != null && server.isEnforceWhitelist();
        String message = ArgusCore.INSTANCE.onPlayerJoin(player.getUUID(), player.hasPermissions(4), whitelistEnabled);
        if (message != null) {
            if (message.contains("Access revoked")) {
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
    }
}
