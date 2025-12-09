package dev.butterflysky.argus.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.LoginIntrospection;
import dev.butterflysky.argus.common.LoginResult;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow private MinecraftServer server;
    @Shadow private Connection connection;
    @Shadow private GameProfile gameProfile;

    @Inject(method = "handleHello", at = @At("TAIL"), cancellable = true)
    private void argus$gateLogin(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (server == null) return;

        GameProfile profile = this.gameProfile;
        if (profile == null) return;

        PlayerList list = server.getPlayerList();
        boolean isOp = LoginIntrospection.isOp(list, profile);
        boolean isWhitelisted = LoginIntrospection.isWhitelisted(list, profile);
        boolean whitelistEnabled = server.isEnforceWhitelist();

        UUID uuid = callUuid(profile);
        String name = callString(profile, new String[] {"getName", "name"});
        LoginResult result = ArgusCore.INSTANCE.onPlayerLogin(uuid, name, isOp, isWhitelisted, whitelistEnabled);
        if (result instanceof LoginResult.Allow) return;

        if (result instanceof LoginResult.Deny deny) {
            if (deny.getRevokeWhitelist()) {
                LoginIntrospection.removeFromWhitelist(list, profile);
            }
            connection.disconnect(Component.literal(deny.getMessage()));
            ci.cancel();
        }
    }

    private UUID callUuid(GameProfile profile) {
        for (String name : new String[] {"getId", "id"}) {
            try {
                var m = profile.getClass().getMethod(name);
                m.setAccessible(true);
                Object res = m.invoke(profile);
                if (res instanceof UUID u) return u;
            } catch (Exception ignored) {
            }
        }
        return UUID.randomUUID();
    }

    private String callString(Object target, String[] methods) {
        for (String name : methods) {
            try {
                var m = target.getClass().getMethod(name);
                m.setAccessible(true);
                Object res = m.invoke(target);
                if (res != null) return res.toString();
            } catch (Exception ignored) {
            }
        }
        return "player";
    }
}
