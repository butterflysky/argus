package dev.butterflysky.domain.events

import dev.butterflysky.domain.MembershipId
import dev.butterflysky.domain.MembershipStatus // Required for status change events
import dev.butterflysky.domain.PlayerId
import dev.butterflysky.eventsourcing.AbstractDomainEvent
import java.time.Instant

/**
 * Base sealed class for all events related to the Membership aggregate.
 */
sealed class MembershipEvent(
    membershipId: MembershipId,
    override val eventType: String
) : AbstractDomainEvent<MembershipId>(membershipId, "Membership", eventType)

/**
 * Event indicating a new membership record was created, usually in a PENDING state.
 */
data class MembershipCreatedEvent(
    val memId: MembershipId, // This is effectively the PlayerId
    val forPlayerId: PlayerId,
    val initialStatus: MembershipStatus = MembershipStatus.PENDING,
    val reason: String? = null, // e.g., "New application submitted"
    val createdAt: Instant = Instant.now()
) : MembershipEvent(memId, "MembershipCreated")

/**
 * Event indicating a membership status changed.
 * This is a more generic event that can cover multiple transitions.
 */
data class MembershipStatusChangedEvent(
    val memId: MembershipId,
    val newStatus: MembershipStatus,
    val oldStatus: MembershipStatus? = null, // Optional: useful for auditing or conditional logic
    val reason: String?,
    val changedBy: PlayerId?, // PlayerId of the moderator or system; null if system initiated internally
    val changeTime: Instant = Instant.now()
) : MembershipEvent(memId, "MembershipStatusChanged")

// We could also have more specific events like:
// - MembershipWhitelistedEvent
// - MembershipBannedEvent
// - MembershipUnwhitelistedEvent
// - MembershipRejectedEvent (if application rejection directly leads to membership rejection)
// However, a single MembershipStatusChangedEvent can be more flexible if the reasons and actors are well-captured.
// For now, MembershipStatusChangedEvent will be used for various transitions.
