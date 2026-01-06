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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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
    private val discordExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "argus-discord").apply { isDaemon = true }
        }

    private enum class DiscordPhase {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING,
        DISABLED,
    }

    @Volatile private var discordPhase = DiscordPhase.STOPPED

    @Volatile private var discordGeneration = 0L

    @Volatile private var discordStartFuture: CompletableFuture<Result<Unit>>? = null

    @Volatile private var discordStartOverride: ((ArgusSettings) -> Result<Unit>)? = null

    @Volatile private var discordStopOverride: (() -> Unit)? = null

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

    fun startDiscord(): CompletableFuture<Result<Unit>> {
        if (discordPhase == DiscordPhase.STARTED) {
            return CompletableFuture.completedFuture(Result.success(Unit))
        }
        discordStartFuture?.let { return it }
        val settings = ArgusConfig.current()
        if (settings.botToken.isBlank() || settings.guildId == null) {
            logger.info("Discord disabled: bot token or guildId not configured; continuing without Discord")
            discordPhase = DiscordPhase.DISABLED
            return CompletableFuture.completedFuture(Result.success(Unit))
        }
        val future = prepareStartFuture("Superseded by a new Discord start")
        val generation = nextDiscordGeneration()
        discordPhase = DiscordPhase.STARTING
        discordExecutor.execute {
            val result = startDiscordImpl(settings)
            finishDiscordStart(generation, result, future)
        }
        return future
    }

    fun reloadConfig(): Result<Unit> =
        initialize().onSuccess {
            scheduleDiscordReload()
        }

    fun reloadConfigAsync(): CompletableFuture<Result<Unit>> {
        val future = prepareStartFuture("Superseded by reload")
        discordExecutor.execute {
            val init = initialize()
            if (init.isFailure) {
                discordStartFuture = null
                future.complete(init)
                return@execute
            }
            val settings = ArgusConfig.current()
            performDiscordReload(settings, future)
        }
        return future
    }

    @JvmStatic
    fun reloadConfigJvm() {
        reloadConfig().onFailure { logger.error("Failed to reload Argus config", it) }
    }

    @JvmStatic
    fun reloadConfigAsyncJvm(): CompletableFuture<String?> {
        return reloadConfigAsync().handle { result, err ->
            when {
                err != null -> err.message ?: "Unknown error"
                result == null -> "Unknown error"
                result.isFailure -> result.exceptionOrNull()?.message ?: "Unknown error"
                else -> null
            }
        }
    }

    @JvmStatic
    fun startDiscordJvm() {
        startDiscord()
    }

    fun stopDiscord() {
        if (discordPhase == DiscordPhase.STOPPING || discordPhase == DiscordPhase.STOPPED) return
        val inflight = discordStartFuture
        discordStartFuture = null
        nextDiscordGeneration()
        discordPhase = DiscordPhase.STOPPING
        inflight?.complete(Result.failure(IllegalStateException("Discord stop requested")))
        discordExecutor.execute {
            stopDiscordImpl()
            discordPhase = DiscordPhase.STOPPED
        }
    }

    private fun scheduleDiscordReload(): CompletableFuture<Result<Unit>> {
        val future = prepareStartFuture("Superseded by reload")
        discordExecutor.execute {
            val settings = ArgusConfig.current()
            performDiscordReload(settings, future)
        }
        return future
    }

    private fun performDiscordReload(
        settings: ArgusSettings,
        future: CompletableFuture<Result<Unit>>,
    ) {
        val generation = nextDiscordGeneration()
        discordPhase = DiscordPhase.STOPPING
        stopDiscordImpl()

        if (settings.botToken.isBlank() || settings.guildId == null) {
            logger.info("Discord disabled: bot token or guildId not configured; continuing without Discord")
            discordPhase = DiscordPhase.DISABLED
            discordStartFuture = null
            future.complete(Result.success(Unit))
            return
        }

        discordPhase = DiscordPhase.STARTING
        val result = startDiscordImpl(settings)
        finishDiscordStart(generation, result, future)
    }

    private fun startDiscordImpl(settings: ArgusSettings): Result<Unit> =
        runCatching {
            discordStartOverride?.invoke(settings) ?: DiscordBridge.start(settings)
        }.getOrElse { Result.failure(it) }

    private fun stopDiscordImpl() {
        try {
            discordStopOverride?.invoke() ?: DiscordBridge.stop()
        } catch (t: Throwable) {
            logger.warn("Discord stop failed: ${t.message}")
        }
    }

    private fun finishDiscordStart(
        generation: Long,
        result: Result<Unit>,
        future: CompletableFuture<Result<Unit>>,
    ) {
        if (generation != discordGeneration || discordPhase != DiscordPhase.STARTING) {
            future.complete(result)
            return
        }
        if (result.isSuccess) {
            discordPhase = DiscordPhase.STARTED
        } else {
            discordPhase = DiscordPhase.STOPPED
            logger.warn("Discord startup skipped/failed: ${result.exceptionOrNull()?.message}")
        }
        discordStartFuture = null
        future.complete(result)
    }

    private fun prepareStartFuture(reason: String): CompletableFuture<Result<Unit>> {
        val previous = discordStartFuture
        val future = CompletableFuture<Result<Unit>>()
        if (previous != null && !previous.isDone) {
            previous.complete(Result.failure(IllegalStateException(reason)))
        }
        discordStartFuture = future
        return future
    }

    private fun nextDiscordGeneration(): Long {
        val next = discordGeneration + 1
        discordGeneration = next
        return next
    }

    fun setDiscordStartOverride(override: ((ArgusSettings) -> Result<Unit>)?) {
        discordStartOverride = override
    }

    fun setDiscordStopOverride(override: (() -> Unit)?) {
        discordStopOverride = override
    }

    internal fun resetDiscordStateForTests() {
        discordPhase = DiscordPhase.STOPPED
        discordGeneration = 0L
        discordStartFuture = null
    }

    fun onPlayerLogin(
        uuid: UUID,
        name: String,
        isOp: Boolean,
        isLegacyWhitelisted: Boolean,
        whitelistEnabled: Boolean,
    ): LoginResult {
        if (isOp || !whitelistEnabled) return LoginResult.Allow

        val discordUp = discordStartedOverride ?: (discordPhase == DiscordPhase.STARTED)
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
            AuditLogger.log(
                action = "MC name changed",
                subject = mcLabel(pdata.mcName, uuid),
                description = "${pdata.mcName} -> $name",
                metadata = auditMeta("uuid" to uuid),
            )
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
                    action = "Access revoked",
                    subject = mcLabel(name, uuid),
                    description = "${if (enforcement) "" else "[DRY-RUN] "}Left Discord guild",
                    metadata =
                        auditMeta(
                            "discord" to discordLabel(pdata.discordName ?: "unknown", pdata.discordId),
                            "uuid" to uuid,
                        ),
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
            AuditLogger.log(
                action = "First login",
                subject = mcLabel(name, uuid),
                actor = discordLabel,
                description = "Allow",
                metadata = auditMeta("uuid" to uuid, "discordId" to pdata?.discordId),
            )
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
        AuditLogger.log(
            action = if (!enforcement) "Access review (dry-run)" else "Access change",
            subject = mcLabel(name, uuid),
            description = message,
            metadata = auditMeta("uuid" to uuid, "discordId" to pdata?.discordId),
        )
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
            AuditLogger.log(
                action = "Legacy unlinked",
                subject = mcLabel(name, uuid),
                description = "Previously whitelisted but unlinked; issued link token",
                metadata = auditMeta("uuid" to uuid, "discordId" to pdata?.discordId, "token" to token),
            )
            CacheStore.appendEvent(
                EventEntry(type = "first_legacy_kick", targetUuid = uuid.toString(), targetDiscordId = pdata?.discordId),
            )
            scheduleSave()
        }
        val msg = prefix(withInviteSuffix("Verification Required: /link $token in Discord"))
        if (!enforcement) {
            AuditLogger.log(
                action = "Legacy unlinked",
                subject = mcLabel(name, uuid),
                description = "[DRY-RUN] Would deny legacy-unlinked: $msg",
                metadata = auditMeta("uuid" to uuid, "token" to token),
            )
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
                    action = "Access revoked",
                    subject = mcLabel(pdata.mcName, uuid),
                    description = "${if (enforcement) "" else "[DRY-RUN] "}Left Discord guild",
                    metadata =
                        auditMeta(
                            "discord" to discordLabel(pdata.discordName ?: "unknown", pdata.discordId),
                            "uuid" to uuid,
                        ),
                )
                if (enforcement) LoginResult.Deny(prefix(withInviteSuffix("Access revoked: left Discord guild"))) else null
            }
            liveAccess -> null
            else -> {
                if (!enforcement) {
                    AuditLogger.log(
                        action = "Access review (dry-run)",
                        subject = mcLabel(pdata.mcName, uuid),
                        description = "Would revoke: missing Discord whitelist role",
                        metadata =
                            auditMeta(
                                "discord" to discordLabel(pdata.discordName ?: "unknown", pdata.discordId),
                                "uuid" to uuid,
                            ),
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
        val preferredDiscordName = discordNick?.takeIf { it.isNotBlank() } ?: discordName
        AuditLogger.log(
            action = "Link complete",
            subject = mcLabel(mcLabel, uuid),
            actor = discordLabel(preferredDiscordName, discordId),
            description = "Discord user linked via token",
            metadata = auditMeta("uuid" to uuid, "discordId" to discordId),
        )
        messenger?.invoke(uuid, prefix("Linked Discord user: $preferredDiscordName"))
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
            AuditLogger.log(
                action = "Whitelist add",
                subject = mcLabel(mcName ?: target.toString(), target),
                actor = actor,
                metadata = auditMeta("uuid" to target, "discordId" to updated.discordId),
            )
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
            AuditLogger.log(
                action = "Whitelist remove",
                subject = mcLabel(current.mcName ?: target.toString(), target),
                actor = actor,
                metadata = auditMeta("uuid" to target, "discordId" to updated.discordId),
            )
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
        AuditLogger.log(
            action = "Application submitted",
            subject = mcLabel(canonical, uuid),
            actor = discordLabel(discordId),
            metadata = auditMeta("uuid" to uuid, "discordId" to discordId, "appId" to appId),
        )
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
        AuditLogger.log(
            action = "Application approved",
            subject = mcLabel(app.mcName, uuid),
            actor = discordLabel(actorDiscordId),
            description = reason ?: "Approved",
            metadata = auditMeta("uuid" to uuid, "discordId" to app.discordId, "appId" to id),
        )
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
        AuditLogger.log(
            action = "Application denied",
            subject = label,
            actor = discordLabel(actorDiscordId),
            description = reason ?: "No reason provided",
            metadata = auditMeta("uuid" to targetUuid, "discordId" to app.discordId, "appId" to id),
        )
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
            AuditLogger.log(
                action = "Warn",
                subject = mcLabel(current.mcName ?: uuid.toString(), uuid),
                actor = discordLabel(actor),
                description = reason,
                metadata = auditMeta("uuid" to uuid, "discordId" to updated.discordId, "warnings" to updated.warnCount),
            )
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
                action = "Ban",
                subject = mcLabel(current.mcName ?: uuid.toString(), uuid),
                actor = discordLabel(actor),
                description = reason,
                metadata =
                    auditMeta(
                        "uuid" to uuid,
                        "discordId" to updated.discordId,
                        "until" to (untilEpochMillis ?: "perm"),
                    ),
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
            AuditLogger.log(
                action = "Unban",
                subject = mcLabel(current.mcName ?: uuid.toString(), uuid),
                actor = discordLabel(actor),
                description = reason ?: "",
                metadata = auditMeta("uuid" to uuid, "discordId" to updated.discordId),
            )
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
            AuditLogger.log(
                action = "Comment",
                subject = mcLabel(uuid.toString(), uuid),
                actor = discordLabel(actor),
                description = note,
                metadata = auditMeta("uuid" to uuid),
            )
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
            parseMojangProfile(resp.body(), json)
        }
}

@Serializable
internal data class MojangProfile(val id: String, val name: String)

internal fun parseMojangProfile(
    body: String,
    json: Json,
): Pair<UUID, String> {
    val profile = json.decodeFromString(MojangProfile.serializer(), body)
    val uuid = profile.id.toDashedUuid()
    return uuid to profile.name
}

private fun String.toDashedUuid(): UUID {
    require(length == 32) { "Bad response" }
    val dashed =
        buildString(36) {
            append(this@toDashedUuid.substring(0, 8))
            append('-')
            append(this@toDashedUuid.substring(8, 12))
            append('-')
            append(this@toDashedUuid.substring(12, 16))
            append('-')
            append(this@toDashedUuid.substring(16, 20))
            append('-')
            append(this@toDashedUuid.substring(20))
        }
    return UUID.fromString(dashed)
}
