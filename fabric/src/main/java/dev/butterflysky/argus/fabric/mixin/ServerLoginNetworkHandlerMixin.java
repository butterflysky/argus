package dev.butterflysky.argus.fabric.mixin;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.LoginIntrospection;
import dev.butterflysky.argus.common.LoginResult;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow private MinecraftServer server;
    @Shadow private ClientConnection connection;
    @Shadow private GameProfile profile;

    @Inject(method = "sendSuccessPacket", at = @At("HEAD"), cancellable = true)
    private void argus$gateLogin(GameProfile gameProfile, CallbackInfo ci) {
        if (gameProfile == null || server == null) return;

        boolean isOp = LoginIntrospection.isOp(server.getPlayerManager(), gameProfile);
        boolean isWhitelisted = LoginIntrospection.isWhitelisted(server.getPlayerManager(), gameProfile);
        boolean whitelistEnabled = server.isEnforceWhitelist();

        var uuid = callUuid(gameProfile);
        var name = callString(gameProfile, new String[] {"getName", "name"});
        LoginResult result = ArgusCore.INSTANCE.onPlayerLogin(uuid, name, isOp, isWhitelisted, whitelistEnabled);
        if (result instanceof LoginResult.Allow) return;

        if (result instanceof LoginResult.Deny deny) {
            if (deny.getRevokeWhitelist()) {
                LoginIntrospection.removeFromWhitelist(server.getPlayerManager(), gameProfile);
            }
            connection.disconnect(Text.literal(deny.getMessage()));
            ci.cancel();
        }
    }

    private java.util.UUID callUuid(GameProfile gameProfile) {
        try {
            var m = gameProfile.getClass().getMethod("getId");
            m.setAccessible(true);
            Object res = m.invoke(gameProfile);
            if (res instanceof java.util.UUID u) return u;
        } catch (Exception ignored) {
        }
        return java.util.UUID.randomUUID();
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
