package dev.butterflysky.argus.common

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
    @Volatile private var discordStarted = false
    @Volatile private var discordStartedOverride: Boolean? = null
    @Volatile private var roleCheckOverride: ((Long) -> Boolean?)? = null
    @Volatile private var messenger: ((UUID, String) -> Unit)? = null
    private val httpClient = HttpClient.newBuilder()
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

    fun reloadConfig(): Result<Unit> = initialize().onSuccess { startDiscord() }

    @JvmStatic
    fun reloadConfigJvm() {
        reloadConfig().onFailure { logger.error("Failed to reload Argus config", it) }
    }

    @JvmStatic
    fun startDiscordJvm() {
        startDiscord()
    }

    fun onPlayerLogin(
        uuid: UUID,
        name: String,
        isOp: Boolean,
        isLegacyWhitelisted: Boolean,
        whitelistEnabled: Boolean
    ): LoginResult {
        if (isOp) return LoginResult.Allow

        val discordUp = discordStartedOverride ?: discordStarted

        if (!whitelistEnabled) {
            return LoginResult.Allow
        }

        // Fallback to vanilla whitelist if Argus/Discord not available
        if (!ArgusConfig.isConfigured() || !discordUp) {
            return if (isLegacyWhitelisted) LoginResult.Allow
            else LoginResult.Deny(prefix(ArgusConfig.current().applicationMessage))
        }
        val pdata = CacheStore.get(uuid)

        // Track Minecraft name changes when we have existing cache data.
        if (pdata != null && pdata.mcName != null && pdata.mcName != name) {
            AuditLogger.log("MC name changed: ${pdata.mcName} -> $name (${uuid})")
            CacheStore.upsert(uuid, pdata.copy(mcName = name))
            CacheStore.save(ArgusConfig.cachePath)
        } else if (pdata != null && pdata.mcName == null) {
            CacheStore.upsert(uuid, pdata.copy(mcName = name))
            CacheStore.save(ArgusConfig.cachePath)
        }

        // Only pay the live Discord check cost if cache would block them and we have a Discord link.
        val liveAccess = if (pdata?.discordId != null && pdata.hasAccess != true) {
            checkWhitelistRole(pdata.discordId)
        } else null

        if (liveAccess != null && pdata != null) {
            CacheStore.upsert(uuid, pdata.copy(hasAccess = liveAccess))
            CacheStore.save(ArgusConfig.cachePath)
        }
        val hasAccess = liveAccess ?: pdata?.hasAccess

        // Active ban check
        if (pdata?.banUntilEpochMillis != null) {
            val until = pdata.banUntilEpochMillis
            val reason = pdata.banReason ?: "Banned"
            if (until == null || until > System.currentTimeMillis()) {
                val remaining = if (until == null) "permanent" else "${(until - System.currentTimeMillis()) / 1000}s remaining"
                return LoginResult.Deny(prefix("$reason ($remaining)"))
            }
        }

        if (hasAccess == true) {
            val seen = CacheStore.eventsSnapshot().any { it.type == "first_allow" && it.targetUuid == uuid.toString() }
            if (!seen) {
                val discordLabel = discordLabel(pdata?.discordName ?: "unlinked", pdata?.discordId)
                AuditLogger.log("First login seen (allow): mc=${mcLabel(name, uuid)} discord=$discordLabel")
                CacheStore.appendEvent(EventEntry(type = "first_allow", targetUuid = uuid.toString(), targetDiscordId = pdata?.discordId))
                CacheStore.save(ArgusConfig.cachePath)
            }
            return LoginResult.Allow
        }

        if (hasAccess == false) {
            val message = if (pdata?.hasAccess == true && liveAccess == false) {
                prefix("Access revoked: missing Discord whitelist role")
            } else prefix(withInviteSuffix(ArgusConfig.current().applicationMessage))
            return LoginResult.Deny(message)
        }

        if (isLegacyWhitelisted) {
            val token = LinkTokenService.issueToken(uuid, name)
            val seenLegacy = CacheStore.eventsSnapshot().any { it.type == "first_legacy_kick" && it.targetUuid == uuid.toString() }
            if (!seenLegacy) {
                AuditLogger.log("Previously whitelisted but unlinked: mc=${mcLabel(name, uuid)} — kicked with link token")
                CacheStore.appendEvent(EventEntry(type = "first_legacy_kick", targetUuid = uuid.toString(), targetDiscordId = pdata?.discordId))
                CacheStore.save(ArgusConfig.cachePath)
            }
            return LoginResult.Deny(prefix(withInviteSuffix("Verification Required: /link $token in Discord")))
        }

        return LoginResult.Deny(prefix(withInviteSuffix(ArgusConfig.current().applicationMessage)))
    }

    fun onPlayerJoin(uuid: UUID, isOp: Boolean, whitelistEnabled: Boolean): String? {
        if (isOp) {
            if (ArgusConfig.isConfigured()) {
                val pdata = CacheStore.get(uuid)
                if (pdata?.discordId == null) {
                    val token = LinkTokenService.issueToken(uuid, pdata?.mcName ?: "player")
                    return prefix(withInviteSuffix("Please link your account in Discord with /link $token"))
                }
                if (pdata.discordName != null) return prefix("Welcome ${pdata.discordName}")
            }
            return null
        }

        if (whitelistEnabled && ArgusConfig.isConfigured()) {
            val kick = refreshAccessOnJoin(uuid)
            if (kick is LoginResult.Deny) return kick.message
        }

        val data = CacheStore.get(uuid) ?: return null
        val name = data.discordName ?: data.mcName ?: "player"
        return prefix("Welcome $name")
    }

    /** Live role check on join; returns Deny to kick if role missing. */
    private fun refreshAccessOnJoin(uuid: UUID): LoginResult? {
        val pdata = CacheStore.get(uuid) ?: return null
        val discordId = pdata.discordId ?: return null
        val liveAccess = checkWhitelistRole(discordId)
        if (liveAccess == null) return null
        CacheStore.upsert(uuid, pdata.copy(hasAccess = liveAccess))
        CacheStore.save(ArgusConfig.cachePath)
        return if (liveAccess) null else LoginResult.Deny(prefix(withInviteSuffix("Access revoked: missing Discord whitelist role")))
    }

    private fun checkWhitelistRole(discordId: Long): Boolean? =
        roleCheckOverride?.invoke(discordId) ?: DiscordBridge.checkWhitelistRole(discordId)

    private fun withInviteSuffix(message: String): String {
        val invite = ArgusConfig.current().discordInviteUrl
        return if (invite.isNullOrBlank()) message else "$message (Join: $invite)"
    }

    private fun prefix(message: String): String = "[argus] $message"

    private fun mcLabel(name: String?, uuid: UUID): String = "${name ?: "minecraft user"} ($uuid)"

    private fun discordLabel(discordId: Long?): String =
        discordId?.let { CacheStore.findByDiscordId(it)?.second?.discordName?.let { n -> "$n ($it)" } ?: "$it" } ?: "unknown"

    private fun discordLabel(name: String, id: Long?): String = if (id != null) "$name ($id)" else name

    @JvmStatic
    fun onPlayerJoinJvm(uuid: UUID, isOp: Boolean, whitelistEnabled: Boolean): String? = onPlayerJoin(uuid, isOp, whitelistEnabled)

    /** Testing hook to emulate Discord availability without real gateway connection. */
    fun setDiscordStartedOverride(value: Boolean?) {
        discordStartedOverride = value
    }

    /** Testing hook to override whitelist role checks (for headless tests). */
    fun setRoleCheckOverride(checker: ((Long) -> Boolean?)?) {
        roleCheckOverride = checker
    }

    fun registerMessenger(handler: (UUID, String) -> Unit) {
        messenger = handler
    }

    fun linkDiscordUser(token: String, discordId: Long, discordName: String, discordNick: String?): Result<String> {
        val uuid = LinkTokenService.consume(token) ?: return Result.failure(IllegalArgumentException("Invalid or expired token"))
        val existing = CacheStore.get(uuid) ?: PlayerData()
        val updated = existing.copy(
            discordId = discordId,
            discordName = discordName,
            discordNick = discordNick,
            hasAccess = true
        )
        CacheStore.upsert(uuid, updated)
        CacheStore.save(ArgusConfig.cachePath)
        CacheStore.appendEvent(EventEntry(type = "link", targetUuid = uuid.toString(), targetDiscordId = discordId, message = "Linked via token"))
        val mcLabel = existing.mcName ?: "minecraft user"
        AuditLogger.log("Linked minecraft user ${mcLabel} ($uuid) to discord user ${discordLabel(discordName, discordId)}")
        messenger?.invoke(uuid, prefix("Linked Discord user: $discordName"))
        return Result.success("Linked successfully.")
    }

    fun whitelistAdd(target: UUID, mcName: String?, actor: String): Result<String> = runCatching {
        val current = CacheStore.get(target) ?: PlayerData()
        val updated = current.copy(hasAccess = true, mcName = mcName ?: current.mcName)
        CacheStore.upsert(target, updated)
        CacheStore.appendEvent(EventEntry(type = "whitelist_add", targetUuid = target.toString(), targetDiscordId = updated.discordId, actorDiscordId = null, message = "by $actor"))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Whitelist add: ${mcLabel(mcName ?: target.toString(), target)} by ${actor}")
        "Whitelisted ${mcName ?: target}"
    }

    fun whitelistRemove(target: UUID, actor: String): Result<String> = runCatching {
        val current = CacheStore.get(target) ?: PlayerData()
        val updated = current.copy(hasAccess = false)
        CacheStore.upsert(target, updated)
        CacheStore.appendEvent(EventEntry(type = "whitelist_remove", targetUuid = target.toString(), targetDiscordId = updated.discordId, message = "by $actor"))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Whitelist remove: ${mcLabel(current.mcName ?: target.toString(), target)} by ${actor}")
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

    fun submitApplication(discordId: Long, mcName: String): Result<String> {
        val profile = lookupProfile(mcName)
        if (profile.isFailure) return profile.map { "" }
        val (uuid, canonical) = profile.getOrThrow()
        val appId = UUID.randomUUID().toString()
        val app = WhitelistApplication(
            id = appId,
            discordId = discordId,
            mcName = canonical,
            resolvedUuid = uuid.toString(),
            status = "pending"
        )
        CacheStore.addApplication(app)
        CacheStore.appendEvent(
            EventEntry(type = "apply_submit", targetUuid = uuid.toString(), targetDiscordId = discordId, message = "Applied as $canonical")
        )
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Application submitted: ${mcLabel(canonical, uuid)} by ${discordLabel(discordId)} id=$appId")
        return Result.success(appId)
    }

    fun listPendingApplications(): List<WhitelistApplication> =
        CacheStore.applicationsSnapshot().filter { it.status == "pending" }.sortedBy { it.submittedAtEpochMillis }

    fun approveApplication(id: String, actorDiscordId: Long, reason: String?): Result<String> {
        val app = CacheStore.getApplication(id) ?: return Result.failure(IllegalArgumentException("Application not found"))
        if (app.status != "pending") return Result.failure(IllegalStateException("Application already decided"))
        val uuid = app.resolvedUuid?.let { UUID.fromString(it) } ?: return Result.failure(IllegalStateException("Application missing resolved UUID"))
        val mcName = app.mcName
        val pd = CacheStore.get(uuid) ?: PlayerData(mcName = mcName)
        val updated = pd.copy(hasAccess = true, mcName = mcName, discordId = app.discordId)
        CacheStore.upsert(uuid, updated)
        val decided = app.copy(status = "approved", decidedAtEpochMillis = System.currentTimeMillis(), decidedByDiscordId = actorDiscordId, reason = reason)
        CacheStore.updateApplication(id) { decided }
        CacheStore.appendEvent(EventEntry(type = "apply_approve", targetUuid = uuid.toString(), targetDiscordId = app.discordId, actorDiscordId = actorDiscordId, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Application approved: ${mcLabel(app.mcName, uuid)} by ${discordLabel(actorDiscordId)}")
        return Result.success("Approved $mcName")
    }

    fun denyApplication(id: String, actorDiscordId: Long, reason: String?): Result<String> {
        val app = CacheStore.getApplication(id) ?: return Result.failure(IllegalArgumentException("Application not found"))
        if (app.status != "pending") return Result.failure(IllegalStateException("Application already decided"))
        val decided = app.copy(status = "denied", decidedAtEpochMillis = System.currentTimeMillis(), decidedByDiscordId = actorDiscordId, reason = reason)
        CacheStore.updateApplication(id) { decided }
        CacheStore.appendEvent(EventEntry(type = "apply_deny", targetUuid = app.resolvedUuid, targetDiscordId = app.discordId, actorDiscordId = actorDiscordId, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        val targetUuid = app.resolvedUuid?.let { UUID.fromString(it) }
        val label = targetUuid?.let { mcLabel(app.mcName, it) } ?: app.mcName
        AuditLogger.log("Application denied: $label id=$id by ${discordLabel(actorDiscordId)} reason=${reason ?: ""}")
        return Result.success("Denied application ${app.mcName}")
    }

    fun warnPlayer(uuid: UUID, actor: Long, reason: String): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(warnCount = current.warnCount + 1)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "warn", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Warn: ${mcLabel(current.mcName ?: uuid.toString(), uuid)} by ${discordLabel(actor)} reason=$reason")
        "Warned ${current.mcName ?: uuid}"
    }

    fun banPlayer(uuid: UUID, actor: Long, reason: String, untilEpochMillis: Long?): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(banReason = reason, banUntilEpochMillis = untilEpochMillis, hasAccess = false)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "ban", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason, untilEpochMillis = untilEpochMillis))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Ban: ${mcLabel(current.mcName ?: uuid.toString(), uuid)} by ${discordLabel(actor)} reason=$reason until=${untilEpochMillis ?: "perm"}")
        "Banned ${current.mcName ?: uuid}"
    }

    fun unbanPlayer(uuid: UUID, actor: Long, reason: String?): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(banReason = null, banUntilEpochMillis = null)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "unban", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Unban: ${mcLabel(current.mcName ?: uuid.toString(), uuid)} by ${discordLabel(actor)} reason=${reason ?: ""}")
        "Unbanned ${current.mcName ?: uuid}"
    }

    fun commentOnPlayer(uuid: UUID, actor: Long, note: String): Result<String> = runCatching {
        CacheStore.appendEvent(EventEntry(type = "comment", targetUuid = uuid.toString(), actorDiscordId = actor, message = note))
        CacheStore.save(ArgusConfig.cachePath)
        AuditLogger.log("Comment on ${mcLabel(uuid.toString(), uuid)} by ${discordLabel(actor)}: $note")
        "Comment recorded"
    }

    fun reviewPlayer(uuid: UUID): List<EventEntry> =
        CacheStore.eventsSnapshot().filter { it.targetUuid == uuid.toString() }

    fun userWarningsAndBan(discordId: Long): Pair<Int, String?> {
        val warnCount = CacheStore.eventsSnapshot().count { it.type == "warn" && it.targetDiscordId == discordId }
        val ban = CacheStore.eventsSnapshot().lastOrNull { it.type == "ban" && it.targetDiscordId == discordId }
        return warnCount to ban?.message
    }

    private fun lookupProfile(name: String): Result<Pair<UUID, String>> = runCatching {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/${name}"))
            .timeout(5.seconds.toJavaDuration())
            .GET()
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) error("Player not found")
        val body = resp.body()
        // Simple parse without pulling a JSON lib here
        val idKey = "\"id\":\""
        val nameKey = "\"name\":\""
        val idStart = body.indexOf(idKey).takeIf { it >= 0 } ?: error("Bad response")
        val nameStart = body.indexOf(nameKey).takeIf { it >= 0 } ?: error("Bad response")
        val idStr = body.substring(idStart + idKey.length).takeWhile { it != '\"' }
        val resolvedName = body.substring(nameStart + nameKey.length).takeWhile { it != '\"' }
        val dashed = "${idStr.substring(0,8)}-${idStr.substring(8,12)}-${idStr.substring(12,16)}-${idStr.substring(16,20)}-${idStr.substring(20)}"
        UUID.fromString(dashed) to resolvedName
    }
}
