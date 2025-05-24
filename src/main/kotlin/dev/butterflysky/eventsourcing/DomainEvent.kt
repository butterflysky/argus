package dev.butterflysky.eventsourcing

import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Represents a generic domain event within the system.
 * All specific domain events should implement this interface.
 */
interface DomainEvent {
    /** A unique identifier for this specific event instance. */
    val eventId: UUID

    /** The timestamp when the event occurred. */
    val occurredAt: Instant

    /** The type of the aggregate root this event pertains to (e.g., "Application", "Membership"). */
    val aggregateType: String

    /** The string representation of the aggregate root's unique identifier. */
    val aggregateId: String

    /** A string descriptor for the specific type of event (e.g., "ApplicationSubmitted", "MembershipApproved"). */
    val eventType: String
}

/**
 * An abstract base class for domain events, providing common implementations for eventId, occurredAt,
 * and deriving aggregateId from a typed aggregate ID.
 *
 * @param TId The type of the aggregate's identifier (e.g., ApplicationId, MembershipId).
 * @property typedAggregateId The specific, typed identifier of the aggregate root instance.
 * @property aggregateType The type of the aggregate root (passed to DomainEvent).
 * @property eventType The specific type of this event (passed to DomainEvent).
 */
abstract class AbstractDomainEvent<TId>(
    val typedAggregateId: TId, // The actual typed ID of the aggregate
    override val aggregateType: String,
    override val eventType: String
) : DomainEvent {
    override val eventId: UUID = UUID.randomUUID()
    override val occurredAt: Instant = Clock.System.now()

    // The aggregateId is the string representation of the typedAggregateId.
    override val aggregateId: String get() = typedAggregateId.toString()
}
