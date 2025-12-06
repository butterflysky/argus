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
 * Core, platform-agnostic logic. Keeps the login path cache-first and non-blocking.
 */
object ArgusCore {
    private val logger = LoggerFactory.getLogger("argus-core")
    @Volatile private var discordStarted = false
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

    @JvmStatic
    fun startDiscordJvm() {
        startDiscord()
    }

    fun onPlayerLogin(
        uuid: UUID,
        name: String,
        isOp: Boolean,
        isLegacyWhitelisted: Boolean
    ): LoginResult {
        val pdata = CacheStore.get(uuid)

        // Active ban check
        if (pdata?.banUntilEpochMillis != null) {
            val until = pdata.banUntilEpochMillis
            val reason = pdata.banReason ?: "Banned"
            if (until == null || until > System.currentTimeMillis()) {
                val remaining = if (until == null) "permanent" else "${(until - System.currentTimeMillis()) / 1000}s remaining"
                return LoginResult.Deny("$reason ($remaining)")
            }
        }

        if (isOp) return LoginResult.Allow

        if (pdata != null) {
            if (pdata.hasAccess) return LoginResult.Allow
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

    @JvmStatic
    fun onPlayerJoinJvm(uuid: UUID, isOp: Boolean): String? = onPlayerJoin(uuid, isOp)

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
        AuditLogger.log("Linked ${uuid} to Discord $discordName (access granted)")
        return Result.success("Linked successfully; access granted.")
    }

    fun whitelistAdd(target: UUID, mcName: String?, actor: String): Result<String> = runCatching {
        val current = CacheStore.get(target) ?: PlayerData()
        val updated = current.copy(hasAccess = true, mcName = mcName ?: current.mcName)
        CacheStore.upsert(target, updated)
        CacheStore.appendEvent(EventEntry(type = "whitelist_add", targetUuid = target.toString(), targetDiscordId = updated.discordId, actorDiscordId = null, message = "by $actor"))
        CacheStore.save(ArgusConfig.cachePath)
        "Whitelisted ${mcName ?: target}"
    }

    fun whitelistRemove(target: UUID, actor: String): Result<String> = runCatching {
        val current = CacheStore.get(target) ?: PlayerData()
        val updated = current.copy(hasAccess = false)
        CacheStore.upsert(target, updated)
        CacheStore.appendEvent(EventEntry(type = "whitelist_remove", targetUuid = target.toString(), targetDiscordId = updated.discordId, message = "by $actor"))
        CacheStore.save(ArgusConfig.cachePath)
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
        return Result.success("Approved $mcName")
    }

    fun denyApplication(id: String, actorDiscordId: Long, reason: String?): Result<String> {
        val app = CacheStore.getApplication(id) ?: return Result.failure(IllegalArgumentException("Application not found"))
        if (app.status != "pending") return Result.failure(IllegalStateException("Application already decided"))
        val decided = app.copy(status = "denied", decidedAtEpochMillis = System.currentTimeMillis(), decidedByDiscordId = actorDiscordId, reason = reason)
        CacheStore.updateApplication(id) { decided }
        CacheStore.appendEvent(EventEntry(type = "apply_deny", targetUuid = app.resolvedUuid, targetDiscordId = app.discordId, actorDiscordId = actorDiscordId, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        return Result.success("Denied application ${app.mcName}")
    }

    fun warnPlayer(uuid: UUID, actor: Long, reason: String): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(warnCount = current.warnCount + 1)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "warn", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        "Warned ${current.mcName ?: uuid}"
    }

    fun banPlayer(uuid: UUID, actor: Long, reason: String, untilEpochMillis: Long?): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(banReason = reason, banUntilEpochMillis = untilEpochMillis, hasAccess = false)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "ban", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason, untilEpochMillis = untilEpochMillis))
        CacheStore.save(ArgusConfig.cachePath)
        "Banned ${current.mcName ?: uuid}"
    }

    fun unbanPlayer(uuid: UUID, actor: Long, reason: String?): Result<String> = runCatching {
        val current = CacheStore.get(uuid) ?: PlayerData(mcName = null)
        val updated = current.copy(banReason = null, banUntilEpochMillis = null)
        CacheStore.upsert(uuid, updated)
        CacheStore.appendEvent(EventEntry(type = "unban", targetUuid = uuid.toString(), targetDiscordId = updated.discordId, actorDiscordId = actor, message = reason))
        CacheStore.save(ArgusConfig.cachePath)
        "Unbanned ${current.mcName ?: uuid}"
    }

    fun commentOnPlayer(uuid: UUID, actor: Long, note: String): Result<String> = runCatching {
        CacheStore.appendEvent(EventEntry(type = "comment", targetUuid = uuid.toString(), actorDiscordId = actor, message = note))
        CacheStore.save(ArgusConfig.cachePath)
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
