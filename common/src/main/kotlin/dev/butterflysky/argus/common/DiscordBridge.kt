package dev.butterflysky.argus.common

import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.channel.ServerTextChannel
import org.javacord.api.entity.permission.Role
import org.javacord.api.entity.server.Server
import org.javacord.api.entity.user.User
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture

object DiscordBridge {
    private val logger = LoggerFactory.getLogger("argus-discord")
    @Volatile private var api: DiscordApi? = null

    fun start(settings: ArgusSettings): Result<Unit> {
        if (settings.botToken.isBlank()) {
            return Result.failure(IllegalStateException("Discord bot token is empty"))
        }
        val guildId = settings.guildId ?: return Result.failure(IllegalStateException("guildId not set"))

        return runCatching {
            val future: CompletableFuture<DiscordApi> = DiscordApiBuilder()
                .setToken(settings.botToken)
                .setAllIntents()
                .login()

            api = future.join()
            val discord = api ?: throw IllegalStateException("Discord API not initialized")
            logger.info("Discord bot logged in as ${discord.yourself.discriminatedName}")

            val server = discord.getServerById(guildId)
                .orElseThrow { IllegalStateException("Guild $guildId not found") }

            val logChannel = settings.logChannelId?.let { server.getTextChannelById(it).orElse(null) }
            AuditLogger.configure { msg -> logToChannel(logChannel, msg) }

            registerRoleListeners(server, settings)
            registerIdentityListeners(server)
        }
    }

    private fun registerRoleListeners(server: Server, settings: ArgusSettings) {
        api?.addUserRoleAddListener { event ->
            if (event.server != server) return@addUserRoleAddListener
            val roles = event.user.getRoles(server)
            handleRoleChange(event.user, roles, server, settings)
        }
        api?.addUserRoleRemoveListener { event ->
            if (event.server != server) return@addUserRoleRemoveListener
            val roles = event.user.getRoles(server)
            handleRoleChange(event.user, roles, server, settings)
        }
    }

    private fun registerIdentityListeners(server: Server) {
        api?.addUserChangeNicknameListener { event ->
            val user = event.user
            if (event.server != server) return@addUserChangeNicknameListener
            AuditLogger.log("Identity Update: ${user.discriminatedName} nick -> ${event.newNickname.orElse("(cleared)")}")
        }
        api?.addUserChangeNameListener { event ->
            AuditLogger.log("Identity Update: ${event.oldName} is now ${event.newName}")
        }
    }

    private fun handleRoleChange(user: User, roles: List<Role>, server: Server, settings: ArgusSettings) {
        val discordId = user.id
        val hasAccess = settings.whitelistRoleId?.let { roles.any { r -> r.id == it } } ?: false
        val isAdmin = settings.adminRoleId?.let { roles.any { r -> r.id == it } } ?: false

        val updated = updatePlayerByDiscord(discordId) { player ->
            player.copy(
                hasAccess = hasAccess,
                isAdmin = isAdmin,
                discordName = user.name,
                discordNick = user.getNickname(server).orElse(null)
            )
        }

        if (updated) {
            AuditLogger.log("Role update: ${user.discriminatedName} -> access=$hasAccess admin=$isAdmin")
            CacheStore.save(ArgusConfig.cachePath)
        }
    }

    private fun updatePlayerByDiscord(discordId: Long, mutator: (PlayerData) -> PlayerData): Boolean {
        val entry = CacheStore.findByDiscordId(discordId) ?: return false
        CacheStore.upsert(entry.first, mutator(entry.second))
        return true
    }

    private fun logToChannel(channel: ServerTextChannel?, message: String) {
        channel?.sendMessage(message)
    }
}
