package dev.butterflysky.argus.common

import kotlinx.serialization.Serializable

@Serializable
data class PlayerData(
    val discordId: Long? = null,
    val hasAccess: Boolean = false,
    val isAdmin: Boolean = false,
    val mcName: String? = null,
    val discordName: String? = null,
    val discordNick: String? = null
)
