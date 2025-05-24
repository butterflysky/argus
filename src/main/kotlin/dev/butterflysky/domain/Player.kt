package dev.butterflysky.domain // Updated package

import java.util.UUID
import java.time.Instant

// PlayerId is now in the same package, explicit import not strictly needed
// import dev.butterflysky.domain.PlayerId

/**
 * Represents a player in the system, uniquely identified by their PlayerId (e.g., Discord ID).
 * This entity links their primary platform identity with their game-specific accounts.
 */
data class Player(
    val id: PlayerId, // Primary identifier (e.g., Discord ID)
    var primaryMinecraftUuid: UUID? = null, // The UUID of their main Minecraft account
    var primaryMinecraftUsername: String? = null, // Current username of their main Minecraft account
    val linkedMinecraftAccounts: MutableMap<UUID, String> = mutableMapOf(), // UUID to Username mapping for all linked MC accounts
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
) {
    // Convenience method to add or update a linked Minecraft account
    fun linkMinecraftAccount(uuid: UUID, username: String) {
        linkedMinecraftAccounts[uuid] = username
        if (primaryMinecraftUuid == null) { // Auto-set as primary if none exists
            primaryMinecraftUuid = uuid
            primaryMinecraftUsername = username
        }
        updatedAt = Instant.now()
    }

    fun unlinkMinecraftAccount(uuid: UUID) {
        linkedMinecraftAccounts.remove(uuid)
        if (primaryMinecraftUuid == uuid) {
            primaryMinecraftUuid = null
            primaryMinecraftUsername = null
            // Optionally, try to set another linked account as primary if available
            linkedMinecraftAccounts.entries.firstOrNull()?.let {
                primaryMinecraftUuid = it.key
                primaryMinecraftUsername = it.value
            }
        }
        updatedAt = Instant.now()
    }
}
