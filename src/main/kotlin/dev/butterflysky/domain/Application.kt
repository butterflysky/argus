package dev.butterflysky.domain // Updated package

import java.time.Instant
import java.util.UUID // For GameAccountId if it's a UUID

// Types are now in the same package, explicit imports not strictly needed
// import dev.butterflysky.domain.ApplicationId
// import dev.butterflysky.domain.PlayerId
// import dev.butterflysky.domain.ApplicationStatus

/**
 * Represents a player's application for whitelisting.
 */
data class Application(
    val id: ApplicationId = ApplicationId(), // Unique ID for this specific application instance
    val playerId: PlayerId, // The player who submitted the application
    val gameAccountId: UUID, // The specific game account (e.g., Minecraft UUID) this application is for
    val minecraftUsername: String, // Minecraft username at the time of application
    var status: ApplicationStatus = ApplicationStatus.PENDING,
    val details: String, // Details or reasons provided by the player
    val submittedAt: Instant = Instant.now(),
    var processedAt: Instant? = null, // When the application was approved/rejected
    var processedBy: PlayerId? = null, // Moderator/System who processed the application
    var processingNotes: String? = null // Notes from the moderator during processing
) {
    fun approve(actor: PlayerId, notes: String? = null) {
        if (status != ApplicationStatus.PENDING) throw IllegalStateException("Application must be PENDING to approve")
        status = ApplicationStatus.APPROVED
        processedAt = Instant.now()
        processedBy = actor
        processingNotes = notes
    }

    fun reject(actor: PlayerId, reason: String, notes: String? = null) {
        if (status != ApplicationStatus.PENDING) throw IllegalStateException("Application must be PENDING to reject")
        status = ApplicationStatus.REJECTED
        processedAt = Instant.now()
        processedBy = actor
        processingNotes = notes ?: reason // If no specific notes, use the reason
    }

    fun reopen(actor: PlayerId, reasonForReopening: String) {
        if (status != ApplicationStatus.REJECTED) throw IllegalStateException("Application must be REJECTED to reopen")
        status = ApplicationStatus.PENDING
        // Clear previous processing info
        processedAt = null
        processedBy = null
        // Keep original submission details, but log who reopened and why (could be part of an audit log)
        // For now, processingNotes can store the reopening reason or it can be a separate field/event.
        processingNotes = "Reopened by ${actor.value}: $reasonForReopening"
    }
}
