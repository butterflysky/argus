package dev.butterflysky.domain // Updated package

import java.util.UUID

/**
 * Unique identifier for a Player, typically based on their primary platform ID (e.g., Discord ID).
 */
@JvmInline
value class PlayerId(val value: String) {
    // Companion object for common PlayerId instances or factory methods if needed in the future
    companion object {
        // Example: A special PlayerId for system-initiated actions
        // val SYSTEM = PlayerId("system")
    }
}
