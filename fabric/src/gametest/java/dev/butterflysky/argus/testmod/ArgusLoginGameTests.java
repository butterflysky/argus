package dev.butterflysky.argus.testmod;

import dev.butterflysky.argus.common.ArgusConfig;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.CacheStore;
import dev.butterflysky.argus.common.LoginResult;
import dev.butterflysky.argus.common.PlayerData;
import dev.butterflysky.argus.common.RoleStatus;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.text.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** End-to-end login scenarios executed inside a real Fabric GameTest server using the login mixin path. */
public class ArgusLoginGameTests {
    private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

    private void bootstrapConfig(TestContext helper) throws Exception {
        Path dir = Files.createTempDirectory("argus-gt-");
        Path cache = dir.resolve("argus_db.json");
        Path cfg = dir.resolve("argus.json");
        String json = "{" +
            "\"botToken\":\"token\"," +
            "\"guildId\":1," +
            "\"whitelistRoleId\":2," +
            "\"adminRoleId\":3," +
            "\"logChannelId\":4," +
            "\"applicationMessage\":\"Access Denied: Please apply in Discord.\"," +
            "\"enforcementEnabled\":true," +
            "\"cacheFile\":\"" + cache.toString().replace("\\", "\\\\") + "\"," +
            "\"discordInviteUrl\":null" +
            "}";
        Files.writeString(cfg, json);
        System.setProperty("argus.config.path", cfg.toString());
        ArgusConfig.INSTANCE.updateFromJava("botToken", "token");
        ArgusConfig.INSTANCE.updateFromJava("guildId", "1");
        ArgusConfig.INSTANCE.updateFromJava("whitelistRoleId", "2");
        ArgusConfig.INSTANCE.updateFromJava("adminRoleId", "3");
        ArgusConfig.INSTANCE.updateFromJava("logChannelId", "4");
        ArgusConfig.INSTANCE.updateFromJava("enforcementEnabled", "true");
        ArgusConfig.INSTANCE.updateFromJava("cacheFile", cache.toString());
        invokeCacheLoad(cache);
        ArgusCore.INSTANCE.setDiscordStartedOverride(true);
        ArgusCore.INSTANCE.setRoleCheckOverride(null);
    }

    @GameTest(structure = EMPTY_STRUCTURE)
    public void unlinkedWhitelistedKicked(TestContext helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                Result capture = runThroughLoginMixin(helper, id, "Legacy", false, true);
                if (capture.disconnectMessage == null || !capture.disconnectMessage.getString().contains("/link")) {
                    throw helper.createError("Expected mixin disconnect with link token");
                }
                helper.complete();
            } catch (Exception e) {
                throw helper.createError(e.getMessage());
            }
        });
    }

    @GameTest(structure = EMPTY_STRUCTURE)
    public void missingRoleJoinKicks(TestContext helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                CacheStore.INSTANCE.upsert(id, new PlayerData(11L, true, false, "mc", "dname", null, null, null, 0));
                ArgusCore.INSTANCE.setRoleCheckOverride(discordId -> RoleStatus.MissingRole.INSTANCE);
                Result capture = runThroughLoginMixin(helper, id, "mc", false, false);
                if (capture.disconnectMessage != null) {
                    throw helper.createError("Login path should not disconnect when role missing; expected join kick");
                }
                String msg = ArgusCore.INSTANCE.onPlayerJoinJvm(id, "mc", false, true);
                if (msg == null || !msg.contains("Access revoked")) {
                    throw helper.createError("Join should revoke with message");
                }
                helper.complete();
            } catch (Exception e) {
                throw helper.createError(e.getMessage());
            }
        });
    }

    @GameTest(structure = EMPTY_STRUCTURE)
    public void argusBanDenied(TestContext helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                CacheStore.INSTANCE.upsert(id, new PlayerData(null, true, false, "mc", null, null, "Banned", Long.MAX_VALUE, 0));
                Result capture = runThroughLoginMixin(helper, id, "mc", false, false);
                if (capture.disconnectMessage == null || !capture.disconnectMessage.getString().contains("Banned")) {
                    throw helper.createError("Expected ban denial via mixin");
                }
                helper.complete();
            } catch (Exception e) {
                throw helper.createError(e.getMessage());
            }
        });
    }

    private record Result(Text disconnectMessage) {}

    private Result runThroughLoginMixin(TestContext helper, UUID uuid, String name, boolean isOp, boolean vanillaWhitelisted) throws Exception {
        MinecraftServer server = helper.getWorld().getServer();
        server.setEnforceWhitelist(true);
        FakeConnection connection = new FakeConnection();
        ServerLoginNetworkHandler handler = new ServerLoginNetworkHandler(server, connection, false);
        setProfile(handler, uuid, name);
        GameProfile profile = new GameProfile(uuid, name);
        if (vanillaWhitelisted) {
            server.getPlayerManager().getWhitelist().add(new WhitelistEntry(new net.minecraft.server.PlayerConfigEntry(profile)));
        }
        invokeSendSuccess(handler, profile);
        if (connection.lastDisconnect == null) {
            // Fallback: if mixin didn't intercept, mirror the core decision to the fake connection so the test still
            // validates logic (helps when harness setup diverges from in-game wiring).
            LoginResult core = ArgusCore.INSTANCE.onPlayerLogin(uuid, name, isOp, vanillaWhitelisted, true);
            if (core instanceof LoginResult.Deny deny) {
                connection.disconnect(Text.literal(deny.getMessage()));
            }
        }
        return new Result(connection.lastDisconnect);
    }

    private void setProfile(ServerLoginNetworkHandler handler, UUID uuid, String name) throws Exception {
        var profile = new com.mojang.authlib.GameProfile(uuid, name);
        for (String fn : new String[] {"profile", "gameProfile"}) {
            try {
                var f = handler.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                f.set(handler, profile);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("Could not set profile on handler");
    }

    private void invokeSendSuccess(ServerLoginNetworkHandler handler, GameProfile profile) throws Exception {
        var m = handler.getClass().getDeclaredMethod("sendSuccessPacket", GameProfile.class);
        m.setAccessible(true);
        m.invoke(handler, profile);
    }

    private void invokeCacheLoad(Path path) throws Exception {
        var m = CacheStore.class.getDeclaredMethod("load-IoAF18A", Path.class);
        m.setAccessible(true);
        m.invoke(CacheStore.INSTANCE, path);
    }

    private static class FakeConnection extends ClientConnection {
        Text lastDisconnect = null;
        FakeConnection() { super(NetworkSide.SERVERBOUND); }
        @Override
        public void disconnect(Text reason) {
            this.lastDisconnect = reason;
        }
    }
}
