package dev.butterflysky.argus.testmod;

import dev.butterflysky.argus.common.ArgusConfig;
import dev.butterflysky.argus.common.ArgusCore;
import dev.butterflysky.argus.common.CacheStore;
import dev.butterflysky.argus.common.LinkTokenService;
import dev.butterflysky.argus.common.LoginResult;
import dev.butterflysky.argus.common.PlayerData;
import dev.butterflysky.argus.common.RoleStatus;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Lightweight scripted login scenarios executed inside a real Fabric GameTest server. */
public class ArgusLoginGameTests implements FabricGameTest {
    private void bootstrapConfig(GameTestHelper helper) throws Exception {
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
        ArgusConfig.load(cfg);
        CacheStore.load(cache);
        ArgusCore.setDiscordStartedOverride(true);
        ArgusCore.setRoleCheckOverride(null);
        // Quiet the test server logs a bit
        helper.getWorld().getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, helper.getWorld().getServer());
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unlinkedWhitelistedKicked(GameTestHelper helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                LoginResult result = ArgusCore.onPlayerLogin(id, "Legacy", false, true, true);
                if (!(result instanceof LoginResult.Deny deny && deny.getMessage().contains("/link"))) {
                    helper.fail("Expected deny with link token");
                    return;
                }
                helper.succeed();
            } catch (Exception e) {
                helper.fail(e.getMessage());
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void missingRoleJoinKicks(GameTestHelper helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                CacheStore.upsert(id, new PlayerData(11L, true, false, "mc", "dname", null, null, null, 0));
                ArgusCore.setRoleCheckOverride(discordId -> RoleStatus.MissingRole);
                LoginResult login = ArgusCore.onPlayerLogin(id, "mc", false, false, true);
                if (!(login instanceof LoginResult.Allow)) {
                    helper.fail("Login should allow (vanilla gates)");
                    return;
                }
                String msg = ArgusCore.onPlayerJoin(id, false, true);
                if (msg == null || !msg.contains("Access revoked")) {
                    helper.fail("Join should revoke with message");
                    return;
                }
                helper.succeed();
            } catch (Exception e) {
                helper.fail(e.getMessage());
            }
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void argusBanDenied(GameTestHelper helper) {
        helper.runAtTick(1, () -> {
            try {
                bootstrapConfig(helper);
                UUID id = UUID.randomUUID();
                CacheStore.upsert(id, new PlayerData(null, true, false, "mc", null, null, "Banned", Long.MAX_VALUE, 0));
                LoginResult login = ArgusCore.onPlayerLogin(id, "mc", false, false, true);
                if (!(login instanceof LoginResult.Deny deny && deny.getMessage().contains("Banned"))) {
                    helper.fail("Expected ban denial");
                    return;
                }
                helper.succeed();
            } catch (Exception e) {
                helper.fail(e.getMessage());
            }
        });
    }
}
