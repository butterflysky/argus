package dev.butterflysky.eventsourcing

import dev.butterflysky.db.WhitelistDatabase
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.datetime.Instant

// TODO: Integrate a JSON serialization library (e.g., kotlinx.serialization)
// For now, we'll use placeholder serialization/deserialization.
private fun serializeEvent(event: DomainEvent): String {
    // Placeholder: In a real implementation, this would serialize the event to JSON.
    return "{ \"type\": \"${event.eventType}\", \"details\": \"${event.toString().replace("\"", "\\\"")}\" }"
}

private fun deserializeEvent(eventData: String, eventType: String, aggregateId: String, eventId: java.util.UUID, occurredAt: Instant): DomainEvent {
    // Placeholder: In a real implementation, this would deserialize JSON to the specific DomainEvent subclass.
    // This is a very basic mock and won't work for actual event replay without a proper implementation.
    // It also doesn't correctly pass all necessary fields to a real event constructor.
    println("WARN: deserializeEvent is a placeholder. Event Type: $eventType, Data: $eventData")
    return object : AbstractDomainEvent<String>(aggregateId, "UnknownAggregate", eventType) {
        override val eventId: java.util.UUID = eventId
        override val occurredAt: Instant = occurredAt
    }
}

class ExposedEventStore : EventStore {

    override fun saveEvents(aggregateId: String, events: List<DomainEvent>, expectedAggregateVersion: Long) {
        transaction {
            // 1. Check for optimistic concurrency: Get current version of the aggregate
            val currentVersion = WhitelistDatabase.EventStoreTable
                .selectAll().where { WhitelistDatabase.EventStoreTable.aggregateId eq aggregateId }
                .orderBy(WhitelistDatabase.EventStoreTable.eventVersion, SortOrder.DESC)
                .limit(1)
                .firstOrNull()?.get(WhitelistDatabase.EventStoreTable.eventVersion) ?: 0L

            if (currentVersion != expectedAggregateVersion) {
                throw ConcurrencyException(
                    "Optimistic lock failed for aggregate $aggregateId. " +
                            "Expected version $expectedAggregateVersion, but was $currentVersion."
                )
            }

            // 2. Insert new events
            var eventVersionCounter = currentVersion
            events.forEach { domainEvent ->
                eventVersionCounter++
                /* // Commenting out this block as AbstractDomainEvent does not have a 'version' property
                if (domainEvent is AbstractDomainEvent<*> && domainEvent.version != eventVersionCounter) {
                     // This check assumes AbstractDomainEvent has a 'version' property reflecting its sequence in the stream being saved.
                     // If AbstractDomainEvent.version is the *aggregate's* version *after* the event, this logic is fine.
                     // If it's meant to be pre-assigned, ensure it matches eventVersionCounter.
                    // For now, we assume the version on the event is correctly set by the aggregate before saving.
                }
                */

                WhitelistDatabase.EventStoreTable.insert {
                    it[this.eventId] = domainEvent.eventId
                    it[this.aggregateId] = aggregateId // Use the passed aggregateId
                    it[this.aggregateType] = domainEvent.aggregateType
                    it[this.eventType] = domainEvent.eventType
                    it[this.eventVersion] = eventVersionCounter // Version of the aggregate *after* this event
                    it[this.eventData] = serializeEvent(domainEvent)
                    it[WhitelistDatabase.EventStoreTable.occurredAt] = domainEvent.occurredAt
                    // storedAt has a default value in the table definition (CurrentTimestamp)
                }
            }
        }
    }

    override fun getEventsForAggregate(aggregateId: String): List<DomainEvent> {
        return transaction {
            WhitelistDatabase.EventStoreTable
                .selectAll().where { WhitelistDatabase.EventStoreTable.aggregateId eq aggregateId }
                .orderBy(WhitelistDatabase.EventStoreTable.eventVersion, SortOrder.ASC)
                .map { row ->
                    deserializeEvent(
                        eventData = row[WhitelistDatabase.EventStoreTable.eventData],
                        eventType = row[WhitelistDatabase.EventStoreTable.eventType],
                        aggregateId = row[WhitelistDatabase.EventStoreTable.aggregateId],
                        eventId = row[WhitelistDatabase.EventStoreTable.eventId],
                        occurredAt = row[WhitelistDatabase.EventStoreTable.occurredAt]
                    )
                }
        }
    }
}
