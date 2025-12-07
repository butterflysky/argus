package dev.butterflysky.argus.common

import kotlinx.serialization.Serializable

/** Cached player state plus moderation fields. */
@Serializable
data class PlayerData(
    val discordId: Long? = null,
    val hasAccess: Boolean = false,
    val isAdmin: Boolean = false,
    val mcName: String? = null,
    val discordName: String? = null,
    val discordNick: String? = null,
    val banReason: String? = null,
    val banUntilEpochMillis: Long? = null,
    val warnCount: Int = 0,
)
