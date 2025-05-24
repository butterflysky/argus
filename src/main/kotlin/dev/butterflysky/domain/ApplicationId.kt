package dev.butterflysky.domain // Updated package

import java.util.UUID

/**
 * Unique identifier for an Application.
 */
@JvmInline
value class ApplicationId(val value: UUID) {
    constructor() : this(UUID.randomUUID())
    override fun toString(): String = value.toString()
}
