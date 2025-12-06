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
import org.javacord.api.interaction.SlashCommandOptionChoice
import org.javacord.api.interaction.SlashCommandOptionType
import org.slf4j.LoggerFactory
import java.awt.Color
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
            "Manage Argus whitelist",
            listOf(
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "add",
                    "Add a player to the Argus whitelist",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        SlashCommandOption.createStringOption("mcname", "Minecraft name (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "remove",
                    "Remove a player from the Argus whitelist",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "status",
                    "Show whitelist status for a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "apply",
                    "Submit a whitelist application",
                    listOf(
                        SlashCommandOption.createStringOption("mcname", "Minecraft username", true)
                    )
                ),
                SlashCommandOption.createSubcommand("list-applications", "List pending applications (admin)"),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "approve",
                    "Approve an application (admin)",
                    listOf(
                        SlashCommandOption.createStringOption("application", "Application ID", true, true),
                        SlashCommandOption.createStringOption("reason", "Reason (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "deny",
                    "Deny an application (admin)",
                    listOf(
                        SlashCommandOption.createStringOption("application", "Application ID", true, true),
                        SlashCommandOption.createStringOption("reason", "Reason (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "comment",
                    "Add an admin comment on a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        SlashCommandOption.createStringOption("note", "Comment text", true)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "review",
                    "Review history for a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "warn",
                    "Warn a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        SlashCommandOption.createStringOption("reason", "Reason", true)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "ban",
                    "Ban a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        SlashCommandOption.createStringOption("reason", "Reason", true),
                        SlashCommandOption.createLongOption("duration_minutes", "Duration in minutes (omit for permanent)", false)
                    )
                ),
                SlashCommandOption.createWithOptions(
                    SlashCommandOptionType.SUB_COMMAND,
                    "unban",
                    "Unban a player",
                    listOf(
                        SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                        SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        SlashCommandOption.createStringOption("reason", "Reason (optional)", false)
                    )
                ),
                SlashCommandOption.createSubcommand("my", "Show your own warnings/bans"),
                SlashCommandOption.createSubcommand("help", "Show command help")
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

        discord.addButtonClickListener { event ->
            val id = event.buttonInteraction.customId
            val interaction = event.interaction
            if (id.startsWith("apps_prev:") || id.startsWith("apps_next:")) {
                val parts = id.split(":")
                val current = parts.getOrNull(1)?.toIntOrNull() ?: return@addButtonClickListener
                val apps = ArgusCore.listPendingApplications()
                val page = if (id.startsWith("apps_prev:")) current - 1 else current + 1
                val slash = interaction.asSlashCommandInteraction().orElse(null) ?: return@addButtonClickListener
                sendApplicationsPage(slash, apps, page)
            }

            if (id.startsWith("apps_apr:") || id.startsWith("apps_deny:")) {
                val appId = id.substringAfter(":")
                val approve = id.startsWith("apps_apr:")
                val result = if (approve) ArgusCore.approveApplication(appId, interaction.user.id, null)
                             else ArgusCore.denyApplication(appId, interaction.user.id, null)
                interaction.createImmediateResponder()
                    .setContent(result.getOrElse { "Failed: ${it.message}" })
                    .respond()
            }
        }

        discord.addAutocompleteCreateListener { event ->
            val interaction = event.autocompleteInteraction
            if (interaction.fullCommandName != "whitelist") return@addAutocompleteCreateListener
            when (interaction.focusedOption.name) {
                "player" -> {
                    val query = interaction.focusedOption.stringValue.orElse("")
                    val choices = CacheStore.snapshot()
                        .entries
                        .asSequence()
                        .filter { entry ->
                            val name = entry.value.mcName
                            when {
                                name == null -> false
                                query.isBlank() -> true
                                else -> name.contains(query, ignoreCase = true)
                            }
                        }
                        .take(25)
                        .map { entry ->
                            val label = "${entry.value.mcName} | ${entry.key}"
                            SlashCommandOptionChoice.create(label, entry.key.toString())
                        }
                        .toList()
                    interaction.respondWithChoices(choices)
                }
                "application" -> {
                    val pending = ArgusCore.listPendingApplications().take(25)
                    val choices = pending.map { SlashCommandOptionChoice.create("${it.mcName} (${it.id.take(8)})", it.id) }
                    interaction.respondWithChoices(choices)
                }
                else -> return@addAutocompleteCreateListener
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
        val sub = interaction.options.firstOrNull()?.name?.lowercase() ?: return
        val publicSubs = setOf("apply", "my", "help")

        val settings = ArgusConfig.current()
        val adminRoleId = settings.adminRoleId
        val member = interaction.user
        val hasAdminRole = adminRoleId != null && member.getRoles(server).any { it.id == adminRoleId }
        if (sub !in publicSubs && !hasAdminRole) {
            interaction.createImmediateResponder()
                .setContent("You need the admin role to run this command.")
                .respond()
            return
        }

        when (sub) {
            "add" -> whitelistAdd(interaction)
            "remove" -> whitelistRemove(interaction)
            "status" -> whitelistStatus(interaction)
            "apply" -> applyForWhitelist(interaction)
            "list-applications" -> listApplications(interaction)
            "approve" -> approveApplication(interaction)
            "deny" -> denyApplication(interaction)
            "comment" -> comment(interaction)
            "review" -> review(interaction)
            "warn" -> warn(interaction)
            "ban" -> ban(interaction)
            "unban" -> unban(interaction)
            "my" -> myStatus(interaction)
            "help" -> help(interaction)
        }
    }

    private fun parseTarget(interaction: SlashCommandInteraction): Pair<UUID, PlayerData?>? {
        val discordOpt = interaction.getArgumentUserValueByName("discord")
        if (discordOpt.isPresent) {
            val discordId = discordOpt.get().id
            val entry = CacheStore.findByDiscordId(discordId)
            if (entry != null) return entry.first to entry.second
            interaction.createImmediateResponder()
                .setContent("Discord user not linked in cache. Provide a player name/UUID instead.")
                .respond()
            return null
        }

        val raw = interaction.getArgumentStringValueByName("player").orElse(null) ?: return null

        val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
        if (uuid != null) return uuid to CacheStore.findByUuid(uuid)

        val match = CacheStore.findByName(raw)
        if (match != null) return match.first to match.second

        interaction.createImmediateResponder()
            .setContent("Player not found. Provide a UUID or a known cached name.")
            .respond()
        return null
    }

    private fun whitelistAdd(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val mcName = interaction.getArgumentStringValueByName("mcname").orElse(target.second?.mcName)
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistAdd(uuid, mcName, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.createImmediateResponder()
            .setContent(message)
            .respond()
    }

    private fun whitelistRemove(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistRemove(uuid, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.createImmediateResponder()
            .setContent(message)
            .respond()
    }

    private fun whitelistStatus(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val status = ArgusCore.whitelistStatus(uuid)
        interaction.createImmediateResponder()
            .setContent(status)
            .respond()
    }

    private fun applyForWhitelist(interaction: SlashCommandInteraction) {
        val mcName = interaction.getArgumentStringValueByName("mcname").orElse(null) ?: return
        val result = ArgusCore.submitApplication(interaction.user.id, mcName)
        val message = result.getOrElse { "Application failed: ${it.message ?: "unknown error"} (try again)" }
        interaction.createImmediateResponder()
            .setContent(message)
            .respond()
    }

    private fun listApplications(interaction: SlashCommandInteraction) {
        val pending = ArgusCore.listPendingApplications()
        if (pending.isEmpty()) {
            interaction.createImmediateResponder().setContent("No pending applications").respond()
            return
        }
        sendApplicationsPage(interaction, pending, 0)
    }

    private fun sendApplicationsPage(interaction: SlashCommandInteraction, apps: List<WhitelistApplication>, page: Int) {
        val pageData = ApplicationsPaginator.paginate(apps, page, 5)
        val slice = pageData.items
        val totalPages = pageData.totalPages
        val embed = EmbedBuilder()
            .setTitle("Pending applications (${pageData.page + 1}/$totalPages)")
            .setColor(Color(0x3498db))
            .setDescription(
                slice.joinToString("\\n") {
                    val ageSeconds = (System.currentTimeMillis() - it.submittedAtEpochMillis) / 1000
                    "- ${it.mcName} (id ${it.id.take(8)}), ${ageSeconds}s ago"
                }
            )
        val controls = mutableListOf<Button>()
        if (pageData.page > 0) controls += Button.secondary("apps_prev:${pageData.page}", "Prev")
        if (pageData.page < totalPages - 1) controls += Button.secondary("apps_next:${pageData.page}", "Next")
        controls += Button.success("apps_apr:${slice.firstOrNull()?.id ?: ""}", "Approve top")
        controls += Button.danger("apps_deny:${slice.firstOrNull()?.id ?: ""}", "Deny top")

        interaction.createImmediateResponder()
            .addEmbed(embed)
            .addComponents(ActionRow.of(controls as List<Button>))
            .respond()
    }

    private fun approveApplication(interaction: SlashCommandInteraction) {
        val id = interaction.getArgumentStringValueByName("application").orElse(null) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.approveApplication(id, interaction.user.id, reason)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Approve failed: ${it.message}" })
            .respond()
    }

    private fun denyApplication(interaction: SlashCommandInteraction) {
        val id = interaction.getArgumentStringValueByName("application").orElse(null) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.denyApplication(id, interaction.user.id, reason)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Deny failed: ${it.message}" })
            .respond()
    }

    private fun comment(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val note = interaction.getArgumentStringValueByName("note").orElse(null) ?: return
        val result = ArgusCore.commentOnPlayer(target.first, interaction.user.id, note)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Comment failed: ${it.message}" })
            .respond()
    }

    private fun review(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val events = ArgusCore.reviewPlayer(target.first).takeLast(10)
        if (events.isEmpty()) {
            interaction.createImmediateResponder().setContent("No history for ${target.first}").respond()
            return
        }
        val lines = events.joinToString("\\n") { "${it.type} at ${it.atEpochMillis} ${it.message ?: ""}" }
        interaction.createImmediateResponder()
            .setContent(lines)
            .respond()
    }

    private fun warn(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null) ?: return
        val result = ArgusCore.warnPlayer(target.first, interaction.user.id, reason)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Warn failed: ${it.message}" })
            .respond()
    }

    private fun ban(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null) ?: return
        val minutes = interaction.getArgumentLongValueByName("duration_minutes").orElse(null)
        val until = minutes?.let { System.currentTimeMillis() + it * 60_000 }
        val result = ArgusCore.banPlayer(target.first, interaction.user.id, reason, until)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Ban failed: ${it.message}" })
            .respond()
    }

    private fun unban(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.unbanPlayer(target.first, interaction.user.id, reason)
        interaction.createImmediateResponder()
            .setContent(result.getOrElse { "Unban failed: ${it.message}" })
            .respond()
    }

    private fun myStatus(interaction: SlashCommandInteraction) {
        val (warnCount, banMsg) = ArgusCore.userWarningsAndBan(interaction.user.id)
        val pd = CacheStore.findByDiscordId(interaction.user.id)?.second
        val activeBan = pd?.banReason
        val text = buildString {
            append("Warnings: $warnCount")
            if (activeBan != null) append("\\nActive ban: $activeBan")
            else if (banMsg != null) append("\\nLast ban: $banMsg")
        }
        interaction.createImmediateResponder()
            .setContent(text.ifBlank { "No records." })
            .respond()
    }

    private fun help(interaction: SlashCommandInteraction) {
        val helpText = """
            /whitelist add player:<mc|uuid> [discord:user] [mcname] — whitelist and link
            /whitelist remove player:<mc|uuid>
            /whitelist status player:<mc|uuid>
            /whitelist apply mcname:<name> — submit application
            /whitelist list-applications — list pending (admin)
            /whitelist approve|deny application:<id> [reason]
            /whitelist warn|ban|unban player:<mc|uuid> [reason] [duration_minutes]
            /whitelist comment|review player:<mc|uuid> [...]
            /whitelist my — see your warnings/bans
        """.trimIndent()
        interaction.createImmediateResponder()
            .setContent(helpText)
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
