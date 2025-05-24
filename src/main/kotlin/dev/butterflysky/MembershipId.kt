package dev.butterflysky // Adjusted package

// Removed import dev.butterflysky.domain.player.PlayerId as PlayerId is now in the same package

/**
 * Unique identifier for a Membership record.
 * For simplicity and a strong link to the Player, this could be the PlayerId itself,
 * or a separate UUID if a Player could theoretically have multiple distinct 'memberships'
 * over time that need to be tracked independently (though this is less likely for this use case).
 * Using PlayerId directly enforces a one-to-one relationship between a Player and their current Membership status.
 */
@JvmInline
value class MembershipId(val value: PlayerId) {
    override fun toString(): String = value.value
}
