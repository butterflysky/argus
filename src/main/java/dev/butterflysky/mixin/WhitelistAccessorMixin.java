package dev.butterflysky.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.WhitelistEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.authlib.GameProfile;
import dev.butterflysky.service.WhitelistService;

/**
 * Mixin to access the whitelist directory and entries
 */
@Mixin(PlayerManager.class)
public class WhitelistAccessorMixin {
    /**
     * When the whitelist is reloaded, we update our cached player list
     */
    @Inject(method = "reloadWhitelist()V", at = @At("RETURN"))
    private void onReloadWhitelist(CallbackInfo info) {
        PlayerManager playerManager = (PlayerManager)(Object)this;
        MinecraftServer server = playerManager.getServer();
        WhitelistService whitelistService = WhitelistService.Companion.getInstance();
        
        // Import whitelist entries from vanilla whitelist to our database
        whitelistService.importExistingWhitelist();
    }
}