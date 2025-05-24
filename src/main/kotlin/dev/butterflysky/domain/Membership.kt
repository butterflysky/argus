package dev.butterflysky.domain // Updated package

import java.time.Instant

// Types are now in the same package, explicit imports not strictly needed
// import dev.butterflysky.domain.MembershipId
// import dev.butterflysky.domain.PlayerId
// import dev.butterflysky.domain.MembershipStatus

/**
 * Represents a player's overall membership status within the community/server.
 */
data class Membership(
    val id: MembershipId, // Derived from PlayerId, ensuring one Membership per Player
    val playerId: PlayerId, // Explicitly store PlayerId for clarity and direct access
    var status: MembershipStatus,
    var statusReason: String? = null, // e.g., ban reason, reason for unwhitelisting
    val createdAt: Instant = Instant.now(), // When the membership record was first created
    var lastStatusChangeAt: Instant = Instant.now(), // When the status last changed
    var lastStatusChangedBy: PlayerId? = null // Who (PlayerId of mod/system) triggered the last status change
) {
    init {
        require(id.value == playerId) { "MembershipId must correspond to the PlayerId." }
    }

    // Private helper to update common fields on status change
    private fun updateStatus(newStatus: MembershipStatus, actor: PlayerId?, reason: String?) {
        this.status = newStatus
        this.lastStatusChangeAt = Instant.now()
        this.lastStatusChangedBy = actor
        this.statusReason = reason
    }

    // Example transition methods - these would be called by a service layer
    // that also handles auditing and side effects (like actual whitelist/ban commands).

    fun moveToWhitelisted(actor: PlayerId) {
        if (status !in listOf(MembershipStatus.PENDING, MembershipStatus.UNWHITELISTED)) {
            throw IllegalStateException("Cannot move to WHITELISTED from $status")
        }
        updateStatus(MembershipStatus.WHITELISTED, actor, "Whitelisted by ${actor.value}")
    }

    fun moveToRejected(actor: PlayerId, reason: String) {
        if (status != MembershipStatus.PENDING) {
            throw IllegalStateException("Cannot move to REJECTED from $status")
        }
        updateStatus(MembershipStatus.REJECTED, actor, reason)
    }

    fun moveToBanned(actor: PlayerId, reason: String) {
        // Can be banned from PENDING, WHITELISTED, REJECTED, UNWHITELISTED
        if (status == MembershipStatus.BANNED) return // Already banned
        updateStatus(MembershipStatus.BANNED, actor, reason)
    }

    fun moveToUnwhitelisted(actor: PlayerId?, reason: String) {
        // actor can be null for system events like leaving Discord
        if (status !in listOf(MembershipStatus.WHITELISTED, MembershipStatus.BANNED)) {
            throw IllegalStateException("Cannot move to UNWHITELISTED from $status. Current: $status, Reason: $reason")
        }
        updateStatus(MembershipStatus.UNWHITELISTED, actor, reason)
    }

    fun moveToPending(actor: PlayerId?, reason: String) {
        // actor can be null for system events like account transfer auto-reapply
        if (status !in listOf(MembershipStatus.REJECTED, MembershipStatus.UNWHITELISTED)) {
            throw IllegalStateException("Cannot move to PENDING from $status")
        }
        updateStatus(MembershipStatus.PENDING, actor, reason)
    }
}
