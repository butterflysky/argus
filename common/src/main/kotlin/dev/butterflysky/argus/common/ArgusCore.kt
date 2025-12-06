package dev.butterflysky.argus.common

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Core, platform-agnostic logic. Keeps the login path cache-first and non-blocking.
 */
object ArgusCore {
    private val logger = LoggerFactory.getLogger("argus-core")
    @Volatile private var discordStarted = false

    fun initialize(): Result<Unit> {
        logger.info("Initializing Argus core (cache-first)")
        return ArgusConfig.load()
            .mapCatching { CacheStore.load(ArgusConfig.cachePath).getOrThrow() }
    }

    fun startDiscord(): Result<Unit> {
        if (discordStarted) return Result.success(Unit)
        val settings = ArgusConfig.current()
        return DiscordBridge.start(settings)
            .onSuccess { discordStarted = true }
            .onFailure { logger.warn("Discord startup skipped/failed: ${it.message}") }
    }

    fun onPlayerLogin(
        uuid: UUID,
        name: String,
        isOp: Boolean,
        isLegacyWhitelisted: Boolean
    ): LoginResult {
        if (isOp) return LoginResult.Allow

        val playerData = CacheStore.get(uuid)

        if (playerData != null) {
            if (playerData.hasAccess) return LoginResult.Allow
            return LoginResult.Deny("Access Denied: Missing Discord Role")
        }

        if (isLegacyWhitelisted) {
            val token = LinkTokenService.issueToken(uuid, name)
            return LoginResult.AllowWithKick("Verification Required: !link $token")
        }

        return LoginResult.Deny(ArgusConfig.current().applicationMessage)
    }

    fun onPlayerJoin(uuid: UUID, isOp: Boolean): String? {
        if (isOp) return null
        val data = CacheStore.get(uuid) ?: return null
        return "Welcome back, ${data.mcName ?: "player"}!"
    }
}
