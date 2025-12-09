package dev.butterflysky.argus.fabric.mixin;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.argus.common.ArgusCore;
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

        boolean isOp = callBool(server.getPlayerManager(), new String[] {"isOperator", "isOp", "canBypassPlayerLimit"}, gameProfile);
        boolean isWhitelisted = callBool(server.getPlayerManager(), new String[] {"isWhitelisted", "isWhiteListed", "isAllowed"}, gameProfile);
        boolean whitelistEnabled = server.isEnforceWhitelist();

        var uuid = callUuid(gameProfile);
        var name = callString(gameProfile, new String[] {"getName", "name"});
        LoginResult result = ArgusCore.INSTANCE.onPlayerLogin(uuid, name, isOp, isWhitelisted, whitelistEnabled);
        if (result instanceof LoginResult.Allow) return;

        if (result instanceof LoginResult.Deny deny) {
            if (deny.getRevokeWhitelist()) {
                removeFromWhitelist(gameProfile);
            }
            connection.disconnect(Text.literal(deny.getMessage()));
            ci.cancel();
        }
    }

    private boolean callBool(Object target, String[] methods, Object arg) {
        for (String name : methods) {
            try {
                var m = target.getClass().getMethod(name, arg.getClass());
                m.setAccessible(true);
                Object result = m.invoke(target, arg);
                if (result instanceof Boolean b) return b;
            } catch (Exception ignored) {
            }
        }
        return false;
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

    private void removeFromWhitelist(GameProfile gameProfile) {
        // Try common method names first.
        try {
            var pm = server.getPlayerManager();
            for (String name : new String[] {"removeFromWhitelist", "removePlayerFromWhitelist"}) {
                try {
                    var m = pm.getClass().getMethod(name, GameProfile.class);
                    m.setAccessible(true);
                    m.invoke(pm, gameProfile);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
            // Fallback: remove via whitelist entry wrapper if available.
            var whitelist = pm.getWhitelist();
            try {
                Class<?> entryCls = Class.forName("net.minecraft.server.WhitelistEntry");
                var ctor = entryCls.getConstructor(GameProfile.class);
                Object entry = ctor.newInstance(gameProfile);
                var remove = whitelist.getClass().getMethod("remove", entryCls);
                remove.invoke(whitelist, entry);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }
}
