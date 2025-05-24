package dev.butterflysky.eventsourcing

/**
 * Interface for an event store, responsible for persisting and retrieving domain events.
 */
interface EventStore {

    /**
     * Saves a list of domain events for a specific aggregate.
     *
     * Implementations should handle optimistic concurrency. The `expectedVersion` is the version
     * of the aggregate *before* these new events were applied. The first event in the list
     * should have a version of `expectedVersion + 1`.
     *
     * @param aggregateId The unique identifier of the aggregate.
     * @param events The list of domain events to save. Each event should have its version/sequence number correctly set.
     * @param expectedAggregateVersion The version of the aggregate before these new events. This is used for optimistic concurrency checks.
     *                                 For a brand new aggregate, this would typically be 0L or a predefined initial version.
     * @throws ConcurrencyException if the `expectedAggregateVersion` does not match the current version of the aggregate in the store.
     */
    fun saveEvents(aggregateId: String, events: List<DomainEvent>, expectedAggregateVersion: Long)

    /**
     * Retrieves all domain events for a specific aggregate, ordered by their sequence/version.
     *
     * @param aggregateId The unique identifier of the aggregate.
     * @return A list of domain events, or an empty list if no events are found for the aggregate.
     */
    fun getEventsForAggregate(aggregateId: String): List<DomainEvent>
}

/**
 * Exception thrown when an optimistic concurrency check fails during event persistence.
 */
class ConcurrencyException(message: String) : RuntimeException(message)
