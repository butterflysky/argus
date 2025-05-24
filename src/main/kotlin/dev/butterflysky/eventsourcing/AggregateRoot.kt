package dev.butterflysky.eventsourcing

/**
 * Represents an Aggregate Root in the domain model, a cluster of domain objects
 * that can be treated as a single unit.
 *
 * @param TId The type of the aggregate's unique identifier.
 * @param TEvent The base type of domain events produced by this aggregate.
 */
interface AggregateRoot<TId, TEvent : DomainEvent> {
    val id: TId
    val version: Long // For optimistic concurrency and event ordering

    /**
     * Retrieves the list of events that have been raised by the aggregate but not yet committed to storage.
     */
    fun getUncommittedEvents(): List<TEvent>

    /**
     * Clears the list of uncommitted events. Typically called after events have been successfully persisted.
     */
    fun clearUncommittedEvents()

    /**
     * Loads the aggregate's state from a history of domain events.
     * This method is used to reconstruct the aggregate to its current state or to a specific version.
     *
     * @param history A list of domain events to apply, in order of occurrence.
     */
    fun loadFromHistory(history: List<TEvent>)

    /**
     * Applies a domain event to the aggregate to change its state.
     * This method is responsible for the actual state mutation based on the event.
     * It's typically called by `raiseEvent` and `loadFromHistory`.
     * Implementations should handle different event types using a `when` expression.
     *
     * @param event The domain event to apply.
     */
    fun applyEvent(event: TEvent)

    /**
     * Records that a new domain event has occurred.
     * This involves applying the event to change the aggregate's state and adding it to the list of uncommitted events.
     *
     * @param event The domain event to record.
     */
    fun recordEvent(event: TEvent)
}

/**
 * Abstract base class for Aggregate Roots providing common implementations for event management.
 *
 * @param TId The type of the aggregate's unique identifier.
 * @param TEvent The base type of domain events produced by this aggregate.
 */
abstract class AbstractAggregateRoot<TId, TEvent : DomainEvent>(
    override val id: TId
) : AggregateRoot<TId, TEvent> {

    override var version: Long = 0L
        protected set // Version should only be incremented internally when events are applied

    private val _uncommittedEvents = mutableListOf<TEvent>()

    override fun getUncommittedEvents(): List<TEvent> = _uncommittedEvents.toList()

    override fun clearUncommittedEvents() {
        _uncommittedEvents.clear()
    }

    override fun loadFromHistory(history: List<TEvent>) {
        history.forEach {
            applyEvent(it) // Apply the event to mutate state
            version++      // Increment version for each historical event
        }
    }

    override fun recordEvent(event: TEvent) {
        applyEvent(event) // Apply the event to mutate state
        _uncommittedEvents.add(event) // Add to uncommitted list
        version++ // Increment version for new events
    }

    // `applyEvent` must be implemented by concrete aggregate roots to handle specific event types.
    // Example in concrete class:
    // override fun applyEvent(event: SpecificDomainEventType) {
    //     when (event) {
    //         is SomeEvent -> { /* change state; this.version++ (if not handled in recordEvent) */ }
    //         is AnotherEvent -> { /* change state; this.version++ */ }
    //     }
    // }
}
