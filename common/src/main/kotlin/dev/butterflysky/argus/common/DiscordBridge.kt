package dev.butterflysky.argus.common

import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.channel.ServerTextChannel
import org.javacord.api.entity.permission.Role
import org.javacord.api.entity.server.Server
import org.javacord.api.entity.user.User
import org.javacord.api.entity.message.embed.EmbedBuilder
import org.javacord.api.entity.message.component.ActionRow
import org.javacord.api.entity.message.component.Button
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import org.javacord.api.interaction.SlashCommandOptionType
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
            registerSlashCommands(server)
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

    private fun registerSlashCommands(server: Server) {
        val discord = api ?: return

        val linkCommand = SlashCommand.with(
            "link",
            "Link your Discord account with a token",
            listOf(
                SlashCommandOption.createStringOption(
                    "token",
                    "Link token from the Minecraft server",
                    true
                )
            )
        )

        val whitelistCommand = SlashCommand.with(
            "whitelist",
            "Manage Argus whitelist (admins only)",
            listOf(
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "add",
                    "Add a player UUID to the Argus whitelist",
                    listOf(
                        SlashCommandOption.createStringOption("uuid", "Player UUID", true),
                        SlashCommandOption.createStringOption("mcname", "Minecraft name (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "remove",
                    "Remove a player UUID from the Argus whitelist",
                    listOf(
                        SlashCommandOption.createStringOption("uuid", "Player UUID", true)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "status",
                    "Show whitelist status for a player UUID",
                    listOf(
                        SlashCommandOption.createStringOption("uuid", "Player UUID", true)
                    )
                )
            )
        )

        discord.bulkOverwriteServerApplicationCommands(server.id, setOf(linkCommand, whitelistCommand)).join()

        discord.addSlashCommandCreateListener { event ->
            val interaction = event.slashCommandInteraction
            if (interaction.server.orElse(null) != server) return@addSlashCommandCreateListener
            when (interaction.commandName.lowercase()) {
                "link" -> handleLinkSlash(interaction)
                "whitelist" -> handleWhitelistSlash(interaction, server)
            }
        }
    }

    private fun handleLinkSlash(interaction: SlashCommandInteraction) {
        val tokenOpt = interaction.getArgumentStringValueByName("token")
        if (!tokenOpt.isPresent) {
            interaction.createImmediateResponder()
                .setContent("Missing token.")
                .respond()
            return
        }
        val token = tokenOpt.get()
        val user = interaction.user
        val server = interaction.server.orElse(null)

        val result = ArgusCore.linkDiscordUser(
            token = token,
            discordId = user.id,
            discordName = user.discriminatedName,
            discordNick = server?.let { user.getDisplayName(it) } ?: user.discriminatedName
        )

        val embed = EmbedBuilder()
            .setTitle("Argus Link")
            .setDescription(
                if (result.isSuccess) {
                    result.getOrNull()
                } else {
                    "Link failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                }
            )
            .setColor(if (result.isSuccess) java.awt.Color(0x2ecc71) else java.awt.Color(0xe74c3c))

        interaction.createImmediateResponder()
            .addEmbed(embed)
            .addComponents(ActionRow.of(Button.primary("ok", "OK")))
            .respond()
    }

    private fun handleWhitelistSlash(interaction: SlashCommandInteraction, server: Server) {
        val settings = ArgusConfig.current()
        val adminRoleId = settings.adminRoleId
        val member = interaction.user
        val hasAdminRole = adminRoleId != null && member.getRoles(server).any { it.id == adminRoleId }
        if (!hasAdminRole) {
            interaction.createImmediateResponder()
                .setContent("You need the admin role to run this command.")
                .respond()
            return
        }

        val sub = interaction.options.firstOrNull()?.name?.lowercase() ?: return
        when (sub) {
            "add" -> whitelistAdd(interaction)
            "remove" -> whitelistRemove(interaction)
            "status" -> whitelistStatus(interaction)
        }
    }

    private fun parseUuid(interaction: SlashCommandInteraction): UUID? {
        val uuidStr = interaction.getArgumentStringValueByName("uuid").orElse(null)
        return runCatching { UUID.fromString(uuidStr) }.getOrElse {
            interaction.createImmediateResponder()
                .setContent("Invalid UUID")
                .respond()
            null
        }
    }

    private fun whitelistAdd(interaction: SlashCommandInteraction) {
        val uuid = parseUuid(interaction) ?: return
        val mcName = interaction.getArgumentStringValueByName("mcname").orElse(null)
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistAdd(uuid, mcName, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.createImmediateResponder()
            .setContent(message)
            .respond()
    }

    private fun whitelistRemove(interaction: SlashCommandInteraction) {
        val uuid = parseUuid(interaction) ?: return
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistRemove(uuid, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.createImmediateResponder()
            .setContent(message)
            .respond()
    }

    private fun whitelistStatus(interaction: SlashCommandInteraction) {
        val uuid = parseUuid(interaction) ?: return
        val status = ArgusCore.whitelistStatus(uuid)
        interaction.createImmediateResponder()
            .setContent(status)
            .respond()
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
