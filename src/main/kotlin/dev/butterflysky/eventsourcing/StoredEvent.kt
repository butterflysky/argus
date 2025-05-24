package dev.butterflysky.eventsourcing

import java.time.Instant
import java.util.UUID

/**
 * Represents a domain event as it is stored in a persistent event store.
 *
 * @property globalEventId A unique identifier for this specific stored event entry (distinct from DomainEvent.eventId which might be the same across retries if not careful, though typically unique).
 * @property eventId The unique ID of the domain event itself (from DomainEvent.eventId).
 * @property aggregateId The identifier of the aggregate to which this event belongs.
 * @property aggregateType The type of the aggregate (e.g., "Application", "Membership").
 * @property eventType The specific class name or type identifier of the domain event.
 * @property eventVersion The version of the aggregate *after* this event was applied. This is the sequence number of the event in the aggregate's stream.
 * @property eventData The serialized payload of the domain event (e.g., as a JSON string).
 * @property occurredAt The timestamp when the domain event originally occurred.
 * @property storedAt The timestamp when this event record was persisted to the store.
 */
data class StoredEvent(
    val globalEventId: UUID = UUID.randomUUID(), // Primary key for the database table
    val eventId: UUID,
    val aggregateId: String,
    val aggregateType: String,
    val eventType: String,
    val eventVersion: Long,
    val eventData: String,      // Serialized event payload (e.g., JSON)
    val occurredAt: Instant,
    val storedAt: Instant = Instant.now()
)
