package dev.butterflysky.argus.common

import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder
import org.javacord.api.entity.channel.ServerTextChannel
import org.javacord.api.entity.intent.Intent
import org.javacord.api.entity.message.MessageFlag
import org.javacord.api.entity.message.component.ActionRow
import org.javacord.api.entity.message.component.Button
import org.javacord.api.entity.message.embed.EmbedBuilder
import org.javacord.api.entity.permission.Role
import org.javacord.api.entity.server.Server
import org.javacord.api.entity.user.User
import org.javacord.api.interaction.ButtonInteraction
import org.javacord.api.interaction.SlashCommand
import org.javacord.api.interaction.SlashCommandInteraction
import org.javacord.api.interaction.SlashCommandOption
import org.javacord.api.interaction.SlashCommandOptionChoice
import org.javacord.api.interaction.SlashCommandOptionType
import org.slf4j.LoggerFactory
import java.awt.Color
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Discord integration using Javacord: slash commands, role/name listeners, and audit logging. */
object DiscordBridge {
    private val logger = LoggerFactory.getLogger("argus-discord")

    @Volatile private var api: DiscordApi? = null

    @Volatile private var serverRef: Server? = null

    @Volatile private var whitelistRoleId: Long? = null

    @Volatile private var adminRoleId: Long? = null

    private val whitelistHandlers: Map<String, (SlashCommandInteraction) -> Unit> by lazy {
        mapOf(
            "add" to ::whitelistAdd,
            "remove" to ::whitelistRemove,
            "status" to ::whitelistStatus,
            "apply" to ::applyForWhitelist,
            "list-applications" to ::listApplications,
            "approve" to ::approveApplication,
            "deny" to ::denyApplication,
            "comment" to ::comment,
            "review" to ::review,
            "warn" to ::warn,
            "ban" to ::ban,
            "unban" to ::unban,
            "my" to ::myStatus,
            "help" to ::help,
        )
    }

    fun stop() {
        try {
            api?.disconnect()?.join()
        } catch (t: Throwable) {
            logger.warn("Error while shutting down Discord", t)
        } finally {
            api = null
            serverRef = null
            whitelistRoleId = null
            adminRoleId = null
        }
    }

    fun start(settings: ArgusSettings): Result<Unit> {
        if (settings.botToken.isBlank()) {
            return Result.failure(IllegalStateException("Discord bot token is empty"))
        }
        val guildId = settings.guildId ?: return Result.failure(IllegalStateException("guildId not set"))

        return runCatching {
            val future: CompletableFuture<DiscordApi> =
                DiscordApiBuilder()
                    .setToken(settings.botToken)
                    .setIntents(Intent.GUILDS, Intent.GUILD_MEMBERS)
                    .login()

            api = future.join()
            val discord = api ?: throw IllegalStateException("Discord API not initialized")
            logger.info("Discord bot logged in as ${discord.yourself.discriminatedName}")

            val server =
                discord.getServerById(guildId)
                    .orElseThrow { IllegalStateException("Guild $guildId not found") }
            serverRef = server
            whitelistRoleId = settings.whitelistRoleId
            adminRoleId = settings.adminRoleId

            val logChannel =
                settings.logChannelId?.let {
                    server.getTextChannelById(it).orElse(null).also { ch ->
                        if (ch == null) {
                            logger.warn("Log channel $it not found or not a text channel; audit messages will stay in console only")
                        }
                    }
                }
            AuditLogger.configure { entry -> logToChannel(logChannel, entry) }

            registerRoleListeners(server)
            registerIdentityListeners(server)
            registerSlashCommands(server)
        }
    }

    private fun registerRoleListeners(server: Server) {
        api?.addUserRoleAddListener { event ->
            if (event.server != server) return@addUserRoleAddListener
            val roles = event.user.getRoles(server)
            handleRoleChange(event.user, roles, server)
        }
        api?.addUserRoleRemoveListener { event ->
            if (event.server != server) return@addUserRoleRemoveListener
            val roles = event.user.getRoles(server)
            handleRoleChange(event.user, roles, server)
        }
    }

    private fun registerIdentityListeners(server: Server) {
        api?.addUserChangeNicknameListener { event ->
            val user = event.user
            if (event.server != server) return@addUserChangeNicknameListener
            val oldNick = event.oldNickname.orElse(null)
            val newNick = event.newNickname.orElse(null)
            if (oldNick == newNick) return@addUserChangeNicknameListener
            applyIdentityChange(
                discordId = user.id,
                oldName = user.name,
                newName = user.name,
                oldNick = oldNick,
                newNick = newNick,
            )
        }
        api?.addUserChangeNameListener { event ->
            val user = event.user
            val oldName = event.oldName
            val newName = event.newName
            if (oldName == newName) return@addUserChangeNameListener
            applyIdentityChange(
                discordId = user.id,
                oldName = oldName,
                newName = newName,
                oldNick = null,
                newNick = null,
            )
        }
    }

    private fun registerSlashCommands(server: Server) {
        val discord = api ?: return

        val linkCommand =
            SlashCommand.with(
                "link",
                "Link your Discord account with a token",
                listOf(
                    SlashCommandOption.createStringOption(
                        "token",
                        "Link token from the Minecraft server",
                        true,
                    ),
                ),
            )

        val whitelistCommand =
            SlashCommand.with(
                "whitelist",
                "Manage Argus whitelist",
                listOf(
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "add",
                        "Add a player to the Argus whitelist",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createStringOption("mcname", "Minecraft name (optional)", false),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "remove",
                        "Remove a player from the Argus whitelist",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "status",
                        "Show whitelist status for a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "apply",
                        "Submit a whitelist application",
                        listOf(
                            SlashCommandOption.createStringOption("mcname", "Minecraft username", true),
                        ),
                    ),
                    SlashCommandOption.createSubcommand("list-applications", "List pending applications (admin)"),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "approve",
                        "Approve an application (admin)",
                        listOf(
                            SlashCommandOption.createStringOption("application", "Application ID", true, true),
                            SlashCommandOption.createStringOption("reason", "Reason (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "deny",
                        "Deny an application (admin)",
                        listOf(
                            SlashCommandOption.createStringOption("application", "Application ID", true, true),
                            SlashCommandOption.createStringOption("reason", "Reason (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "comment",
                        "Add an admin comment on a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createStringOption("note", "Comment text", true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "review",
                        "Review history for a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "warn",
                        "Warn a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createStringOption("reason", "Reason", true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "ban",
                        "Ban a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createStringOption("reason", "Reason", true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                            SlashCommandOption.createLongOption("duration_minutes", "Duration in minutes (omit for permanent)", false),
                        ),
                    ),
                    SlashCommandOption.createWithOptions(
                        SlashCommandOptionType.SUB_COMMAND,
                        "unban",
                        "Unban a player",
                        listOf(
                            SlashCommandOption.createStringOption("player", "Player UUID or name", true, true),
                            SlashCommandOption.createUserOption("discord", "Discord user (optional)", false),
                            SlashCommandOption.createStringOption("reason", "Reason (optional)", false),
                        ),
                    ),
                    SlashCommandOption.createSubcommand("my", "Show your own warnings/bans"),
                    SlashCommandOption.createSubcommand("help", "Show command help"),
                ),
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
                val result =
                    if (approve) {
                        ArgusCore.approveApplication(appId, interaction.user.id, null)
                    } else {
                        ArgusCore.denyApplication(appId, interaction.user.id, null)
                    }
                event.buttonInteraction.replyText(result.getOrElse { "Failed: ${it.message}" })
            }
        }

        discord.addAutocompleteCreateListener { event ->
            val interaction = event.autocompleteInteraction
            if (interaction.fullCommandName != "whitelist") return@addAutocompleteCreateListener
            when (interaction.focusedOption.name) {
                "player" -> {
                    val query = interaction.focusedOption.stringValue.orElse("")
                    val choices =
                        CacheStore.snapshot()
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
            interaction.replyText("Missing token.")
            return
        }
        val token = tokenOpt.get()
        val user = interaction.user
        val server = interaction.server.orElse(null)
        val resolved = resolveDisplayNames(user, server)

        val result =
            ArgusCore.linkDiscordUser(
                token = token,
                discordId = user.id,
                discordName = resolved.preferred,
                discordNick = resolved.nickname,
            )

        val embed =
            EmbedBuilder()
                .setTitle("Argus Link")
                .setDescription(
                    if (result.isSuccess) {
                        result.getOrNull()
                    } else {
                        "Link failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    },
                )
                .setColor(if (result.isSuccess) java.awt.Color(0x2ecc71) else java.awt.Color(0xe74c3c))

        interaction.replyEphemeral()
            .addEmbed(embed)
            .respond()
    }

    private fun handleWhitelistSlash(
        interaction: SlashCommandInteraction,
        server: Server,
    ) {
        val sub = interaction.options.firstOrNull()?.name?.lowercase() ?: return
        val publicSubs = setOf("apply", "my", "help")

        val settings = ArgusConfig.current()
        val adminRoleId = settings.adminRoleId
        val member = interaction.user
        val hasAdminRole = adminRoleId != null && member.getRoles(server).any { it.id == adminRoleId }
        if (sub !in publicSubs && !hasAdminRole) {
            interaction.replyText("You need the admin role to run this command.")
            return
        }
        whitelistHandlers[sub]?.invoke(interaction)
    }

    private fun parseTarget(interaction: SlashCommandInteraction): Pair<UUID, PlayerData?>? {
        val discordOpt = interaction.getArgumentUserValueByName("discord")
        if (discordOpt.isPresent) {
            val discordId = discordOpt.get().id
            val entry = CacheStore.findByDiscordId(discordId)
            if (entry != null) return entry.first to entry.second
            interaction.replyText("Discord user not linked in cache. Provide a player name/UUID instead.")
            return null
        }

        val raw = interaction.getArgumentStringValueByName("player").orElse(null) ?: return null

        val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
        if (uuid != null) return uuid to CacheStore.findByUuid(uuid)

        val match = CacheStore.findByName(raw)
        if (match != null) return match.first to match.second

        interaction.replyText("Player not found. Provide a UUID or a known cached name.")
        return null
    }

    private fun whitelistAdd(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val mcName = interaction.getArgumentStringValueByName("mcname").orElse(target.second?.mcName)
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistAdd(uuid, mcName, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.replyText(message)
    }

    private fun whitelistRemove(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val actor = interaction.user.discriminatedName
        val result = ArgusCore.whitelistRemove(uuid, actor)
        val message = result.getOrElse { "Failed: ${it.message}" }

        interaction.replyText(message)
    }

    private fun whitelistStatus(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val uuid = target.first
        val status = ArgusCore.whitelistStatus(uuid)
        interaction.replyText(status)
    }

    private fun applyForWhitelist(interaction: SlashCommandInteraction) {
        val mcName = interaction.getArgumentStringValueByName("mcname").orElse(null) ?: return
        val result = ArgusCore.submitApplication(interaction.user.id, mcName)
        val message = result.getOrElse { "Application failed: ${it.message ?: "unknown error"} (try again)" }
        interaction.replyText(message)
    }

    private fun listApplications(interaction: SlashCommandInteraction) {
        val pending = ArgusCore.listPendingApplications()
        if (pending.isEmpty()) {
            interaction.replyText("No pending applications")
            return
        }
        sendApplicationsPage(interaction, pending, 0)
    }

    private fun sendApplicationsPage(
        interaction: SlashCommandInteraction,
        apps: List<WhitelistApplication>,
        page: Int,
    ) {
        val pageData = ApplicationsPaginator.paginate(apps, page, 5)
        val slice = pageData.items
        val totalPages = pageData.totalPages
        val embed =
            EmbedBuilder()
                .setTitle("Pending applications (${pageData.page + 1}/$totalPages)")
                .setColor(Color(0x3498db))
                .setDescription(
                    slice.joinToString("\n") {
                        val ageSeconds = (System.currentTimeMillis() - it.submittedAtEpochMillis) / 1000
                        "- ${it.mcName} (id ${it.id.take(8)}), ${ageSeconds}s ago"
                    },
                )
        val controls = mutableListOf<Button>()
        if (pageData.page > 0) controls += Button.secondary("apps_prev:${pageData.page}", "Prev")
        if (pageData.page < totalPages - 1) controls += Button.secondary("apps_next:${pageData.page}", "Next")
        controls += Button.success("apps_apr:${slice.firstOrNull()?.id ?: ""}", "Approve top")
        controls += Button.danger("apps_deny:${slice.firstOrNull()?.id ?: ""}", "Deny top")

        interaction.replyEphemeral()
            .addEmbed(embed)
            .addComponents(ActionRow.of(controls as List<Button>))
            .respond()
    }

    private fun approveApplication(interaction: SlashCommandInteraction) {
        val id = interaction.getArgumentStringValueByName("application").orElse(null) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.approveApplication(id, interaction.user.id, reason)
        interaction.replyText(result.getOrElse { "Approve failed: ${it.message}" })
    }

    private fun denyApplication(interaction: SlashCommandInteraction) {
        val id = interaction.getArgumentStringValueByName("application").orElse(null) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.denyApplication(id, interaction.user.id, reason)
        interaction.replyText(result.getOrElse { "Deny failed: ${it.message}" })
    }

    private fun comment(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val note = interaction.getArgumentStringValueByName("note").orElse(null) ?: return
        val result = ArgusCore.commentOnPlayer(target.first, interaction.user.id, note)
        interaction.replyText(result.getOrElse { "Comment failed: ${it.message}" })
    }

    private fun review(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val events = ArgusCore.reviewPlayer(target.first).takeLast(10)
        if (events.isEmpty()) {
            interaction.replyText("No history for ${target.first}")
            return
        }
        val lines = events.joinToString("\\n") { "${it.type} at ${it.atEpochMillis} ${it.message ?: ""}" }
        interaction.replyText(lines)
    }

    private fun warn(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null) ?: return
        val result = ArgusCore.warnPlayer(target.first, interaction.user.id, reason)
        interaction.replyText(result.getOrElse { "Warn failed: ${it.message}" })
    }

    private fun ban(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null) ?: return
        val minutes = interaction.getArgumentLongValueByName("duration_minutes").orElse(null)
        val until = minutes?.let { System.currentTimeMillis() + it * 60_000 }
        val result = ArgusCore.banPlayer(target.first, interaction.user.id, reason, until)
        interaction.replyText(result.getOrElse { "Ban failed: ${it.message}" })
    }

    private fun unban(interaction: SlashCommandInteraction) {
        val target = parseTarget(interaction) ?: return
        val reason = interaction.getArgumentStringValueByName("reason").orElse(null)
        val result = ArgusCore.unbanPlayer(target.first, interaction.user.id, reason)
        interaction.replyText(result.getOrElse { "Unban failed: ${it.message}" })
    }

    private fun myStatus(interaction: SlashCommandInteraction) {
        val (warnCount, banMsg) = ArgusCore.userWarningsAndBan(interaction.user.id)
        val pd = CacheStore.findByDiscordId(interaction.user.id)?.second
        val activeBan = pd?.banReason
        val text =
            buildString {
                append("Warnings: $warnCount")
                if (activeBan != null) {
                    append("\\nActive ban: $activeBan")
                } else if (banMsg != null) {
                    append("\\nLast ban: $banMsg")
                }
            }
        interaction.replyText(text.ifBlank { "No records." })
    }

    private fun help(interaction: SlashCommandInteraction) {
        val helpText =
            """
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
        interaction.replyText(helpText)
    }

    private fun handleRoleChange(
        user: User,
        roles: List<Role>,
        server: Server,
    ) {
        val discordId = user.id
        val hasAccess = whitelistRoleId?.let { rid -> roles.any { r -> r.id == rid } } ?: false
        val isAdmin = adminRoleId?.let { rid -> roles.any { r -> r.id == rid } } ?: false
        val newName = user.name
        val newNick = user.getNickname(server).orElse(null)
        applyRoleUpdate(discordId, newName, newNick, hasAccess, isAdmin)
    }

    private fun logToChannel(
        channel: ServerTextChannel?,
        entry: AuditEntry,
    ) {
        val embed =
            EmbedBuilder()
                .setTitle(entry.action)
                .setColor(Color(0x3498db))
        entry.description?.let { embed.setDescription(it) }
        entry.subject?.let { embed.addField("Subject", it, false) }
        entry.actor?.let { embed.addField("Actor", it, false) }
        if (entry.metadata.isNotEmpty()) {
            embed.setFooter(entry.metadata.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
        }

        channel?.sendMessage(embed)?.exceptionally {
            logger.warn("Failed to send audit log to Discord channel: ${it.message}")
            null
        }
    }

    private fun saveCache() = CacheStore.enqueueSave(ArgusConfig.cachePath)

    /**
     * Live-check whether a Discord user currently has the whitelist role.
     *  - HasRole: member is in guild and has role
     *  - MissingRole: member is in guild but missing role
     *  - NotInGuild: member not in guild
     *  - Indeterminate: timeout or API unavailable
     */
    fun checkWhitelistStatus(
        discordId: Long,
        timeoutMillis: Long = 2000,
    ): RoleStatus {
        val api = api ?: return RoleStatus.Indeterminate
        val server = serverRef ?: return RoleStatus.Indeterminate
        val roleId = whitelistRoleId ?: return RoleStatus.Indeterminate
        return try {
            val member =
                server.requestMember(discordId).orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).join()
            val roles = member.getRoles(server)
            if (roles.any { it.id == roleId }) RoleStatus.HasRole else RoleStatus.MissingRole
        } catch (ex: Exception) {
            val cause = (ex as? java.util.concurrent.CompletionException)?.cause ?: ex
            val msg = cause.message ?: ""
            return when {
                cause is java.util.concurrent.TimeoutException -> RoleStatus.Indeterminate
                cause.javaClass.simpleName.contains("Unknown") && msg.contains("Member", true) -> RoleStatus.NotInGuild
                msg.contains("Unknown Member", true) || msg.contains("Unknown user", true) -> RoleStatus.NotInGuild
                else -> RoleStatus.Indeterminate
            }
        }
    }

    private fun discordLabel(
        name: String?,
        id: Long,
    ) = "${name ?: "unknown"} ($id)"

    private data class ResolvedNames(val preferred: String, val nickname: String?)

    private fun resolveDisplayNames(
        user: User,
        server: Server?,
    ): ResolvedNames {
        val nickname = server?.let { user.getNickname(it).orElse(null) }?.takeIf { it.isNotBlank() }
        val display = server?.let { user.getDisplayName(it) }?.takeIf { it.isNotBlank() }
        val username = user.name
        val preferred = nickname ?: display ?: username
        return ResolvedNames(preferred, nickname)
    }

    // ---------- Headless/test hooks ----------
    internal fun applyIdentityChange(
        discordId: Long,
        oldName: String? = null,
        newName: String? = null,
        oldNick: String? = null,
        newNick: String? = null,
    ) {
        val entry = CacheStore.findByDiscordId(discordId)
        if (entry != null) {
            val (uuid, pd) = entry
            val nameChanged = newName != null && pd.discordName != null && pd.discordName != newName
            val nickChanged = newNick != pd.discordNick
            val updated =
                pd.copy(
                    discordName = newName ?: pd.discordName,
                    discordNick = newNick,
                )
            CacheStore.upsert(uuid, updated)
            if (nameChanged) {
                AuditLogger.log(
                    action = "Discord name changed",
                    subject = discordLabel(newName ?: pd.discordName ?: "unknown", discordId),
                    description = "${pd.discordName} -> $newName",
                    metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
                )
            }
            if (nickChanged) {
                AuditLogger.log(
                    action = "Discord nick changed",
                    subject = discordLabel(newName ?: pd.discordName ?: "unknown", discordId),
                    description = "${pd.discordNick ?: "(none)"} -> ${newNick ?: "(none)"}",
                    metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
                )
            }
            saveCache()
        } else {
            val lbl = discordLabel(newName ?: oldName ?: "unknown", discordId)
            if (oldName != null && newName != null && oldName != newName) {
                AuditLogger.log(
                    action = "Discord name changed",
                    subject = lbl,
                    description = "$oldName -> $newName",
                    metadata = auditMeta("discordId" to discordId),
                )
            }
            if (oldNick != newNick) {
                AuditLogger.log(
                    action = "Discord nick changed",
                    subject = lbl,
                    description = "${oldNick ?: "(none)"} -> ${newNick ?: "(none)"}",
                    metadata = auditMeta("discordId" to discordId),
                )
            }
        }
    }

    internal fun applyRoleUpdate(
        discordId: Long,
        discordName: String,
        discordNick: String?,
        hasAccess: Boolean,
        isAdmin: Boolean,
    ) {
        val entry = CacheStore.findByDiscordId(discordId) ?: return
        val (uuid, player) = entry
        val nameChanged = player.discordName != null && player.discordName != discordName
        val nickChanged = player.discordNick != discordNick

        val updated =
            player.copy(
                hasAccess = hasAccess,
                isAdmin = isAdmin,
                discordName = discordName,
                discordNick = discordNick,
            )
        CacheStore.upsert(uuid, updated)

        val labelName = discordNick ?: discordName
        if (nameChanged) {
            AuditLogger.log(
                action = "Discord name changed",
                subject = discordLabel(labelName, discordId),
                description = "${player.discordName} -> $discordName",
                metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
            )
        }
        if (nickChanged) {
            AuditLogger.log(
                action = "Discord nick changed",
                subject = discordLabel(labelName, discordId),
                description = "${player.discordNick ?: "(none)"} -> ${discordNick ?: "(none)"}",
                metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
            )
        }
        val whitelistedLabel = if (hasAccess) "whitelisted=true" else "whitelisted=false"
        val adminPart =
            if (isAdmin != (player.isAdmin == true)) " admin=$isAdmin" else ""
        AuditLogger.log(
            action = "Role update",
            subject = discordLabel(labelName, discordId),
            description = "$whitelistedLabel$adminPart",
            metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
        )
        saveCache()
    }

    private fun SlashCommandInteraction.replyEphemeral() = createImmediateResponder().setFlags(MessageFlag.EPHEMERAL)

    private fun ButtonInteraction.replyEphemeral() = createImmediateResponder().setFlags(MessageFlag.EPHEMERAL)

    private fun SlashCommandInteraction.replyText(content: String) = replyEphemeral().setContent(content).respond()

    private fun ButtonInteraction.replyText(content: String) = replyEphemeral().setContent(content).respond()
}
