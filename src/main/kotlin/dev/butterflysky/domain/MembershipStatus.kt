package dev.butterflysky.domain // Updated package

/**
 * Represents the membership status of a player in the community/server.
 */
enum class MembershipStatus {
    PENDING,       // Player has applied or is being considered, awaiting initial whitelist/rejection
    WHITELISTED,   // Player is actively whitelisted and can join
    REJECTED,      // Player's application was denied
    BANNED,        // Player is banned from the server
    UNWHITELISTED  // Player was previously whitelisted but has been removed (e.g., left Discord, account transfer, ban revoked)
}
