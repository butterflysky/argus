package dev.butterflysky.argus.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Platform-agnostic core for Argus.
 *
 * Responsibilities:
 * - Load config/cache (.bak fallback).
 * - Cache-only login decisions (never call Discord on login thread).
 * - Discord bridge startup (skips safely when unconfigured).
 * - Whitelist + moderation flows (applications, bans, warnings, comments).
 */
object ArgusCore {
    private val logger = LoggerFactory.getLogger("argus-core")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var discordStarted = false

    @Volatile private var discordStartedOverride: Boolean? = null

    @Volatile private var roleCheckOverride: ((Long) -> RoleStatus?)? = null

    @Volatile private var messenger: ((UUID, String) -> Unit)? = null

    @Volatile private var banMirror: ((UUID, String?, String, Long?) -> Unit)? = null

    @Volatile private var unbanMirror: ((UUID) -> Unit)? = null
    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(5.seconds.toJavaDuration())
            .build()

    fun initialize(): Result<Unit> {
        logger.info("Initializing Argus core (cache-first)")
        return ArgusConfig.load()
            .mapCatching { CacheStore.load(ArgusConfig.cachePath).getOrThrow() }
    }

    @JvmStatic
    fun initializeJvm() {
        initialize().onFailure { logger.error("Failed to initialize Argus core", it) }
    }

    fun startDiscord(): Result<Unit> {
        if (discordStarted) return Result.success(Unit)
        val settings = ArgusConfig.current()
        if (settings.botToken.isBlank() || settings.guildId == null) {
            logger.info("Discord disabled: bot token or guildId not configured; continuing without Discord")
            return Result.success(Unit)
        }
        return DiscordBridge.start(settings)
            .onSuccess { discordStarted = true }
            .onFailure { logger.warn("Discord startup skipped/failed: ${it.message}") }
    }

    fun reloadConfig(): Result<Unit> =
        initialize().onSuccess {
            DiscordBridge.stop()
            discordStarted = false
            startDiscord()
        }

    @JvmStatic
    fun reloadConfigJvm() {
        reloadConfig().onFailure { logger.error("Failed to reload Argus config", it) }
    }

    @JvmStatic
    fun startDiscordJvm() {
        startDiscord()
    }

    fun stopDiscord() {
        DiscordBridge.stop()
        discordStarted = false
    }

    fun onPlayerLogin(
        uuid: UUID,
        name: String,
        isOp: Boolean,
        isLegacyWhitelisted: Boolean,
        whitelistEnabled: Boolean,
    ): LoginResult {
        if (isOp || !whitelistEnabled) return LoginResult.Allow

        val discordUp = discordStartedOverride ?: discordStarted
        val configured = ArgusConfig.isConfigured()
        val pdata = if (configured) CacheStore.get(uuid) else null

        if (!configured || !discordUp) {
            // Fall back to vanilla whitelist when Discord/config is unavailable; still honor Argus bans.
            checkActiveBan(pdata)?.let { return it }
            return LoginResult.Allow
        }

        val enforcement = ArgusConfig.current().enforcementEnabled
        val synced = syncMinecraftName(uuid, name, pdata)
        val liveStatus = computeLiveStatus(synced)
        val hasAccess = reconcileAccess(uuid, name, synced, liveStatus, enforcement)

        checkActiveBan(synced)?.let { return it }

        if (isLegacyWhitelisted && (synced?.discordId == null)) {
            return handleLegacyKick(uuid, name, synced, enforcement)
        }

        if (hasAccess == true) {
            auditFirstAllow(uuid, name, synced)
            return LoginResult.Allow
        }

        if (hasAccess == false) {
            logAccessLoss(uuid, name, synced, liveStatus, enforcement)
            return LoginResult.Allow
        }

        // Stranger/unlinked-but-not-in-whitelist path: allow vanilla to decide. Courtesy handled on join.
        return LoginResult.Allow
    }

    fun onPlayerJoin(
        uuid: UUID,
        isOp: Boolean,
        whitelistEnabled: Boolean,
        mcName: String? = null,
    ): String? {
        val nameHint = mcName?.takeIf { it.isNotBlank() }

        if (isOp) {
            if (ArgusConfig.isConfigured()) {
                val pdata = CacheStore.get(uuid)
                if (pdata?.discordId == null) {
                    val token = LinkTokenService.issueToken(uuid, nameHint ?: pdata?.mcName ?: "player")
                    return prefix(withInviteSuffix("Please link your account in Discord with /link $token"))
                }
                if (pdata.discordName != null) return prefix("Welcome ${pdata.discordName}")
            }
            return null
        }

        if (whitelistEnabled && ArgusConfig.isConfigured()) {
            val pdata = CacheStore.get(uuid)
            if (pdata == null) {
                val token = LinkTokenService.issueToken(uuid, nameHint ?: "player")
                return if (ArgusConfig.current().enforcementEnabled) {
                    prefix(withInviteSuffix("Link required to join: use /link $token in Discord"))
                } else {
                    prefix(withInviteSuffix("Please link your account in Discord with /link $token"))
                }
            }
            if (pdata.discordId == null) {
                val token = LinkTokenService.issueToken(uuid, nameHint ?: pdata.mcName ?: "player")
                return if (ArgusConfig.current().enforcementEnabled) {
                    prefix(withInviteSuffix("Link required to join: use /link $token in Discord"))
                } else {
                    prefix(withInviteSuffix("Please link your account in Discord with /link $token"))
                }
            }
            val kick = refreshAccessOnJoin(uuid)
            if (kick is LoginResult.Deny) return kick.message
        }

        val data = CacheStore.get(uuid) ?: return null
        if (data.hasAccess == false) return null
        val name = data.discordName ?: data.mcName ?: nameHint ?: "player"
        return prefix("Welcome $name")
    }

    private fun syncMinecraftName(
        uuid: UUID,
        name: String,
        pdata: PlayerData?,
    ): PlayerData? {
        if (pdata == null) return null
        if (pdata.mcName == null) {
            val updated = pdata.copy(mcName = name)
            CacheStore.upsert(uuid, updated)
            scheduleSave()
            return updated
        }
        if (pdata.mcName != name) {
            AuditLogger.log("MC name changed: ${pdata.mcName} -> $name ($uuid)")
            val updated = pdata.copy(mcName = name)
            CacheStore.upsert(uuid, updated)
            scheduleSave()
            return updated
        }
        return pdata
    }

    private fun computeLiveStatus(pdata: PlayerData?): RoleStatus? =
        if (pdata?.discordId != null && pdata.hasAccess != true) {
            checkWhitelistStatus(pdata.discordId)
        } else {
            null
        }

    private fun reconcileAccess(
        uuid: UUID,
        name: String,
        pdata: PlayerData?,
        liveStatus: RoleStatus?,
        enforcement: Boolean,
    ): Boolean? {
        if (liveStatus != null && liveStatus != RoleStatus.Indeterminate && pdata != null) {
            val updatedAccess = liveStatus == RoleStatus.HasRole
            if (enforcement) {
                CacheStore.upsert(uuid, pdata.copy(hasAccess = updatedAccess))
                scheduleSave()
            }
            if (liveStatus == RoleStatus.NotInGuild) {
                AuditLogger.log(
                    "${if (enforcement) "" else "[DRY-RUN] "}Access revoked: left Discord guild discord=${discordLabel(
                        pdata.discordName ?: "unknown",
                        pdata.discordId,
                    )} mc=${mcLabel(name, uuid)}",
                )
            }
        }
        return when (liveStatus) {
            RoleStatus.HasRole -> true
            RoleStatus.MissingRole, RoleStatus.NotInGuild -> false
            RoleStatus.Indeterminate, null -> pdata?.hasAccess
        }
    }

    private fun checkActiveBan(pdata: PlayerData?): LoginResult? {
        val until = pdata?.banUntilEpochMillis ?: return null
        if (until > System.currentTimeMillis()) {
            val reason = pdata.banReason ?: "Banned"
            val remaining = "${(until - System.currentTimeMillis()) / 1000}s remaining"
            return LoginResult.Deny(prefix("$reason ($remaining)"))
        }
        return null
    }

    private fun auditFirstAllow(
        uuid: UUID,
        name: String,
        pdata: PlayerData?,
    ) {
        val seen = CacheStore.eventsSnapshot().any { it.type == "first_allow" && it.targetUuid == uuid.toString() }
        if (!seen) {
            val discordLabel = discordLabel(pdata?.discordName ?: "unlinked", pdata?.discordId)
            AuditLogger.log("First login seen (allow): mc=${mcLabel(name, uuid)} discord=$discordLabel")
            CacheStore.appendEvent(EventEntry(type = "first_allow", targetUuid = uuid.toString(), targetDiscordId = pdata?.discordId))
            scheduleSave()
        }
    }

    private fun logAccessLoss(
        uuid: UUID,
        name: String,
        pdata: PlayerData?,
        liveStatus: RoleStatus?,
        enforcement: Boolean,
    ) {
        val message =
            when {
                liveStatus == RoleStatus.NotInGuild -> "Access revoked: not in Discord guild"
                liveStatus == RoleStatus.MissingRole || (pdata?.hasAccess == true && liveStatus == RoleStatus.MissingRole) ->
                    "Access revoked: missing Discord whitelist role"
                else -> withInviteSuffix(ArgusConfig.current().applicationMessage)
            }
        AuditLogger.log("${if (!enforcement) "[DRY-RUN] " else ""}$message mc=${mcLabel(name, uuid)}")
    }

    private fun handleLegacyKick(
        uuid: UUID,
        name: String,
        pdata: PlayerData?,
        enforcement: Boolean,
    ): LoginResult {
        val token = LinkTokenService.issueToken(uuid, name)
        val seenLegacy = CacheStore.eventsSnapshot().any { it.type == "first_legacy_kick" && it.targetUuid == uuid.toString() }
        if (!seenLegacy) {
            AuditLogger.log("Previously whitelisted but unlinked: mc=${mcLabel(name, uuid)} — kicked with link token")
            CacheStore.appendEvent(
                EventEntry(type = "first_legacy_kick", targetUuid = uuid.toString(), targetDiscordId = pdata?.discordId),
            )
            scheduleSave()
        }
        val msg = prefix(withInviteSuffix("Verification Required: /link $token in Discord"))
        if (!enforcement) {
            AuditLogger.log("[DRY-RUN] Would deny legacy-unlinked: mc=${mcLabel(name, uuid)} reason=$msg")
            return LoginResult.Allow
        }
        return LoginResult.Deny(msg, revokeWhitelist = true)
    }

    /** Live role check on join; returns Deny to kick if role missing. */
    private fun refreshAccessOnJoin(uuid: UUID): LoginResult? {
        val pdata = CacheStore.get(uuid) ?: return null
        val discordId = pdata.discordId ?: return null
        val enforcement = ArgusConfig.current().enforcementEnabled
        val liveStatus = checkWhitelistStatus(discordId)
        if (liveStatus == RoleStatus.Indeterminate) return null
        val liveAccess = liveStatus == RoleStatus.HasRole
        CacheStore.upsert(uuid, pdata.copy(hasAccess = liveAccess))
        scheduleSave()
        return when {
            liveStatus == RoleStatus.NotInGuild -> {
                AuditLogger.log(
                    "${if (enforcement) "" else "[DRY-RUN] "}Access revoked: left Discord guild discord=${discordLabel(
                        pdata.discordName ?: "unknown",
                        pdata.discordId,
                    )} mc=${mcLabel(pdata.mcName, uuid)}",
                )
                if (enforcement) LoginResult.Deny(prefix(withInviteSuffix("Access revoked: left Discord guild"))) else null
            }
            liveAccess -> null
            else -> {
                if (!enforcement) {
                    AuditLogger.log(
                        "[DRY-RUN] Would revoke: missing Discord whitelist role discord=${discordLabel(
                            pdata.discordName ?: "unknown",
                            pdata.discordId,
                        )} mc=${mcLabel(pdata.mcName, uuid)}",
                    )
                    null
                } else {
                    LoginResult.Deny(prefix(withInviteSuffix("Access revoked: missing Discord whitelist role")))
                }
            }
        }
    }

    private fun checkWhitelistStatus(discordId: Long): RoleStatus? =
        roleCheckOverride?.invoke(discordId) ?: DiscordBridge.checkWhitelistStatus(discordId)

    private fun withInviteSuffix(message: String): String {
        val invite = ArgusConfig.current().discordInviteUrl
        return if (invite.isNullOrBlank()) message else "$message (Join: $invite)"
    }

    private fun prefix(message: String): String = "[argus] $message"

    private fun scheduleSave() = CacheStore.enqueueSave(ArgusConfig.cachePath)

    private fun mcLabel(
        name: String?,
        uuid: UUID,
    ): String = "${name ?: "minecraft user"} ($uuid)"

    private fun discordLabel(discordId: Long?): String =
        discordId?.let { CacheStore.findByDiscordId(it)?.second?.discordName?.let { n -> "$n ($it)" } ?: "$it" } ?: "unknown"

    private fun discordLabel(
        name: String,
        id: Long?,
    ): String = if (id != null) "$name ($id)" else name

    @JvmStatic
    fun onPlayerJoinJvm(
        uuid: UUID,
        isOp: Boolean,
        whitelistEnabled: Boolean,
    ): String? = onPlayerJoin(uuid, isOp, whitelistEnabled)

    fun onPlayerJoinJvm(
        uuid: UUID,
        name: String?,
        isOp: Boolean,
        whitelistEnabled: Boolean,
    ): String? = onPlayerJoin(uuid, isOp, whitelistEnabled, name)

    /** Testing hook to emulate Discord availability without real gateway connection. */
    fun setDiscordStartedOverride(value: Boolean?) {
        discordStartedOverride = value
    }

    /** Testing hook to override whitelist role checks (for headless tests). */
    fun setRoleCheckOverride(checker: ((Long) -> RoleStatus?)?) {
        roleCheckOverride = checker
    }

    fun registerMessenger(handler: (UUID, String) -> Unit) {
        messenger = handler
    }

    /** Register hooks for mirroring Argus bans into the platform ban list. */
    fun registerBanSync(
        ban: ((UUID, String?, String, Long?) -> Unit)?,
        unban: ((UUID) -> Unit)?,
    ) {
        banMirror = ban
        unbanMirror = unban
    }

    fun linkDiscordUser(
        token: String,
        discordId: Long,
        discordName: String,
        discordNick: String?,
    ): Result<String> {
        val tokenData = LinkTokenService.consume(token) ?: return Result.failure(IllegalArgumentException("Invalid or expired token"))
        val uuid = tokenData.uuid
        val existing = CacheStore.get(uuid) ?: PlayerData(mcName = tokenData.mcName)
        val updated =
            existing.copy(
                discordId = discordId,
                discordName = discordName,
                discordNick = discordNick,
                hasAccess = true,
                mcName = existing.mcName ?: tokenData.mcName,
            )
        CacheStore.upsert(uuid, updated)
        scheduleSave()
        CacheStore.appendEvent(
            EventEntry(type = "link", targetUuid = uuid.toString(), targetDiscordId = discordId, message = "Linked via token"),
        )
        val mcLabel = updated.mcName ?: tokenData.mcName ?: "minecraft user"
        AuditLogger.log("Linked minecraft user $mcLabel ($uuid) to discord user ${discordLabel(discordName, discordId)}")
        messenger?.invoke(uuid, prefix("Linked Discord user: $discordName"))
        return Result.success("Linked successfully.")
    }

    fun whitelistAdd(
        target: UUID,
        mcName: String?,
        actor: String,
    ): Result<String> =
        runCatching {
            val current = CacheStore.get(target) ?: PlayerData()
            val updated = current.copy(hasAccess = true, mcName = mcName ?: current.mcName)
            CacheStore.upsert(target, updated)
            CacheStore.appendEvent(
                EventEntry(
                    type = "whitelist_add",
                    targetUuid = target.toString(),
                    targetDiscordId = updated.discordId,
                    actorDiscordId = null,
                    message = "by $actor",
                ),
            )
            scheduleSave()
            AuditLogger.log("Whitelist add: ${mcLabel(mcName ?: target.toString(), target)} by $actor")
            "Whitelisted ${mcName ?: target}"
        }

    fun whitelistRemove(
        target: UUID,
        actor: String,
    ): Result<String> =
        runCatching {
            val current = CacheStore.get(target) ?: PlayerData()
            val updated = current.copy(hasAccess = false)
            CacheStore.upsert(target, updated)
            CacheStore.appendEvent(
                EventEntry(
                    type = "whitelist_remove",
                    targetUuid = target.toString(),
                    targetDiscordId = updated.discordId,
                    message = "by $actor",
                ),
            )
            scheduleSave()
            AuditLogger.log("Whitelist remove: ${mcLabel(current.mcName ?: target.toString(), target)} by $actor")
            "Removed ${current.mcName ?: target} from whitelist"
        }

    fun whitelistStatus(target: UUID): String {
        val entry = CacheStore.get(target) ?: return "No entry for $target"
        return buildString {
            append("hasAccess=${entry.hasAccess}")
            if (entry.mcName != null) append(" mcName=${entry.mcName}")
            if (entry.discordId != null) append(" discordId=${entry.discordId}")
            if (entry.banUntilEpochMillis != null) append(" banned=true")
        }
    }

    fun submitApplication(
        discordId: Long,
        mcName: String,
    ): Result<String> {
        val profile = lookupProfile(mcName)
        if (profile.isFailure) return profile.map { "" }
        val (uuid, canonical) = profile.getOrThrow()
        val appId = UUID.randomUUID().toString()
        val app =
            WhitelistApplication(
                id = appId,
                discordId = discordId,
                mcName = canonical,
                resolvedUuid = uuid.toString(),
                status = "pending",
            )
        CacheStore.addApplication(app)
        CacheStore.appendEvent(
            EventEntry(type = "apply_submit", targetUuid = uuid.toString(), targetDiscordId = discordId, message = "Applied as $canonical"),
        )
        scheduleSave()
        AuditLogger.log("Application submitted: ${mcLabel(canonical, uuid)} by ${discordLabel(discordId)} id=$appId")
        return Result.success(appId)
    }

    fun listPendingApplications(): List<WhitelistApplication> =
        CacheStore.applicationsSnapshot().filter { it.status == "pending" }.sortedBy { it.submittedAtEpochMillis }

    fun approveApplication(
        id: String,
        actorDiscordId: Long,
        reason: String?,
    ): Result<String> {
        val app = CacheStore.getApplication(id) ?: return Result.failure(IllegalArgumentException("Application not found"))
        if (app.status != "pending") return Result.failure(IllegalStateException("Application already decided"))
        val uuid =
            app.resolvedUuid?.let {
                UUID.fromString(it)
            } ?: return Result.failure(IllegalStateException("Application missing resolved UUID"))
        val mcName = app.mcName
        val pd = CacheStore.get(uuid) ?: PlayerData(mcName = mcName)
        val updated = pd.copy(hasAccess = true, mcName = mcName, discordId = app.discordId)
        CacheStore.upsert(uuid, updated)
        val decided =
            app.copy(
                status = "approved",
                decidedAtEpochMillis = System.currentTimeMillis(),
                decidedByDiscordId = actorDiscordId,
                reason = reason,
            )
        CacheStore.updateApplication(id) { decided }
        CacheStore.appendEvent(
            EventEntry(
                type = "apply_approve",
                targetUuid = uuid.toString(),
                targetDiscordId = app.discordId,
                actorDiscordId = actorDiscordId,
                message = reason,
            ),
        )
        scheduleSave()
        AuditLogger.log("Application approved: ${mcLabel(app.mcName, uuid)} by ${discordLabel(actorDiscordId)}")
        return Result.success("Approved $mcName")
    }

    fun denyApplication(
        id: String,
        actorDiscordId: Long,
        reason: String?,
    ): Result<String> {
        val app = CacheStore.getApplication(id) ?: return Result.failure(IllegalArgumentException("Application not found"))
        if (app.status != "pending") return Result.failure(IllegalStateException("Application already decided"))
        val decided =
            app.copy(
                status = "denied",
                decidedAtEpochMillis = System.currentTimeMillis(),
                decidedByDiscordId = actorDiscordId,
                reason = reason,
            )
        CacheStore.updateApplication(id) { decided }
        CacheStore.appendEvent(
            EventEntry(
                type = "apply_deny",
                targetUuid = app.resolvedUuid,
                targetDiscordId = app.discordId,
                actorDiscordId = actorDiscordId,
                message = reason,
            ),
        )
        scheduleSave()
        val targetUuid = app.resolvedUuid?.let { UUID.fromString(it) }
        val label = targetUuid?.let { mcLabel(app.mcName, it) } ?: app.mcName
        AuditLogger.log("Application denied: $label id=$id by ${discordLabel(actorDiscordId)} reason=${reason ?: ""}")
        return Result.success("Denied application ${app.mcName}")
    }

    fun warnPlayer(
        uuid: UUID,
        actor: Long,
        reason: String,
    ): Result<String> =
        runCatching {
            val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
            val updated = current.copy(warnCount = current.warnCount + 1)
            CacheStore.upsert(uuid, updated)
            CacheStore.appendEvent(
                EventEntry(
                    type = "warn",
                    targetUuid = uuid.toString(),
                    targetDiscordId = updated.discordId,
                    actorDiscordId = actor,
                    message = reason,
                ),
            )
            scheduleSave()
            AuditLogger.log("Warn: ${mcLabel(current.mcName ?: uuid.toString(), uuid)} by ${discordLabel(actor)} reason=$reason")
            "Warned ${current.mcName ?: uuid}"
        }

    fun banPlayer(
        uuid: UUID,
        actor: Long,
        reason: String,
        untilEpochMillis: Long?,
    ): Result<String> =
        runCatching {
            val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
            val updated = current.copy(banReason = reason, banUntilEpochMillis = untilEpochMillis, hasAccess = false)
            CacheStore.upsert(uuid, updated)
            banMirror?.invoke(uuid, current.mcName, reason, untilEpochMillis)
            CacheStore.appendEvent(
                EventEntry(
                    type = "ban",
                    targetUuid = uuid.toString(),
                    targetDiscordId = updated.discordId,
                    actorDiscordId = actor,
                    message = reason,
                    untilEpochMillis = untilEpochMillis,
                ),
            )
            scheduleSave()
            AuditLogger.log(
                "Ban: ${mcLabel(
                    current.mcName ?: uuid.toString(),
                    uuid,
                )} by ${discordLabel(actor)} reason=$reason until=${untilEpochMillis ?: "perm"}",
            )
            "Banned ${current.mcName ?: uuid}"
        }

    fun unbanPlayer(
        uuid: UUID,
        actor: Long,
        reason: String?,
    ): Result<String> =
        runCatching {
            val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
            val updated = current.copy(banReason = null, banUntilEpochMillis = null)
            CacheStore.upsert(uuid, updated)
            unbanMirror?.invoke(uuid)
            CacheStore.appendEvent(
                EventEntry(
                    type = "unban",
                    targetUuid = uuid.toString(),
                    targetDiscordId = updated.discordId,
                    actorDiscordId = actor,
                    message = reason,
                ),
            )
            scheduleSave()
            AuditLogger.log("Unban: ${mcLabel(current.mcName ?: uuid.toString(), uuid)} by ${discordLabel(actor)} reason=${reason ?: ""}")
            "Unbanned ${current.mcName ?: uuid}"
        }

    fun commentOnPlayer(
        uuid: UUID,
        actor: Long,
        note: String,
    ): Result<String> =
        runCatching {
            CacheStore.appendEvent(EventEntry(type = "comment", targetUuid = uuid.toString(), actorDiscordId = actor, message = note))
            scheduleSave()
            AuditLogger.log("Comment on ${mcLabel(uuid.toString(), uuid)} by ${discordLabel(actor)}: $note")
            "Comment recorded"
        }

    fun reviewPlayer(uuid: UUID): List<EventEntry> = CacheStore.eventsSnapshot().filter { it.targetUuid == uuid.toString() }

    fun userWarningsAndBan(discordId: Long): Pair<Int, String?> {
        val warnCount = CacheStore.eventsSnapshot().count { it.type == "warn" && it.targetDiscordId == discordId }
        val ban = CacheStore.eventsSnapshot().lastOrNull { it.type == "ban" && it.targetDiscordId == discordId }
        return warnCount to ban?.message
    }

    private fun lookupProfile(name: String): Result<Pair<UUID, String>> =
        runCatching {
            val req =
                HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/$name"))
                    .timeout(5.seconds.toJavaDuration())
                    .GET()
                    .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) error("Player not found")
            val profile = json.decodeFromString(MojangProfile.serializer(), resp.body())
            val id = profile.id
            require(id.length == 32) { "Bad response" }
            val dashed =
                listOf(
                    id.substring(0, 8),
                    id.substring(8, 12),
                    id.substring(12, 16),
                    id.substring(16, 20),
                    id.substring(20),
                ).joinToString("-")
            UUID.fromString(dashed) to profile.name
        }

    @Serializable
    private data class MojangProfile(val id: String, val name: String)
}
