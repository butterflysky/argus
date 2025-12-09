package dev.butterflysky.argus.common

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

abstract class ArgusTestBase {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun resetArgusState() {
        CacheStore.flushSaves()
        val configPath = tempDir.resolve("argus.json")
        val cachePath = tempDir.resolve("argus_db.json")
        System.setProperty("argus.config.path", configPath.toString())
        Files.deleteIfExists(configPath)
        Files.deleteIfExists(cachePath)
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, Json { prettyPrint = true }.encodeToString(ArgusSettings()))
        ArgusConfig.load(configPath)
        ArgusConfig.update("cacheFile", cachePath.toString())
        CacheStore.load(ArgusConfig.cachePath)
        ArgusCore.setDiscordStartedOverride(null)
        ArgusCore.setRoleCheckOverride(null)
        AuditLogger.configure(null)
        ArgusCore.registerMessenger { _, _ -> }
        ArgusCore.registerBanSync(null, null)
        LinkTokenService.listActive().forEach { LinkTokenService.consume(it.token) }
    }

    protected fun configureArgus(enforcement: Boolean = true) {
        ArgusConfig.update("botToken", "token")
        ArgusConfig.update("guildId", "1234567890")
        ArgusConfig.update("whitelistRoleId", "2345678901")
        ArgusConfig.update("adminRoleId", "3456789012")
        ArgusConfig.update("enforcementEnabled", enforcement.toString())
    }
}
