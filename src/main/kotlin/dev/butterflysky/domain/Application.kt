package dev.butterflysky.domain

import dev.butterflysky.domain.events.*
import dev.butterflysky.eventsourcing.AbstractAggregateRoot
import java.time.Instant
import java.util.UUID

class Application private constructor(
    id: ApplicationId, // From AbstractAggregateRoot
    // Fields that are set once at creation via ApplicationSubmittedEvent
    val playerId: PlayerId,
    val gameAccountId: UUID,
    val minecraftUsername: String,
    val details: String,
    val submittedAt: Instant
) : AbstractAggregateRoot<ApplicationId, ApplicationEvent>(id) {

    // State fields managed by events
    lateinit var status: ApplicationStatus
        private set
    var processedAt: Instant? = null
        private set
    var processedBy: PlayerId? = null
        private set
    var processingNotes: String? = null
        private set

    companion object {
        fun submit(
            id: ApplicationId = ApplicationId(),
            playerId: PlayerId,
            gameAccountId: UUID,
            minecraftUsername: String,
            details: String,
            submittedAt: Instant = Instant.now()
        ): Application {
            val app = Application(id, playerId, gameAccountId, minecraftUsername, details, submittedAt)
            val event = ApplicationSubmittedEvent(
                appId = id,
                submittedByPlayerId = playerId,
                forGameAccountId = gameAccountId,
                forMinecraftUsername = minecraftUsername,
                applicationDetails = details,
                submissionTime = submittedAt
            )
            app.recordEvent(event)
            return app
        }
    }

    // --- Command methods --- (Record events)
    fun approve(actor: PlayerId, notes: String? = null) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Application status has not been initialized. Cannot approve.")
        }
        val currentStatus = this.status // status is now definitely initialized
        if (currentStatus != ApplicationStatus.PENDING) {
            throw IllegalStateException("Application must be PENDING to approve. Current status: $currentStatus")
        }
        recordEvent(ApplicationApprovedEvent(this.id, actor, Instant.now(), notes))
    }

    fun reject(actor: PlayerId, reason: String, notes: String? = null) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Application status has not been initialized. Cannot reject.")
        }
        val currentStatus = this.status // status is now definitely initialized
        if (currentStatus != ApplicationStatus.PENDING) {
            throw IllegalStateException("Application must be PENDING to reject. Current status: $currentStatus")
        }
        recordEvent(ApplicationRejectedEvent(this.id, actor, Instant.now(), reason, notes))
    }

    fun reopen(actor: PlayerId, reasonForReopening: String) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Application status has not been initialized. Cannot reopen.")
        }
        val currentStatus = this.status // status is now definitely initialized
        if (currentStatus != ApplicationStatus.REJECTED) {
            throw IllegalStateException("Application must be REJECTED to reopen. Current status: $currentStatus")
        }
        recordEvent(ApplicationReopenedEvent(this.id, actor, Instant.now(), reasonForReopening))
    }

    // --- Event Sourcing: Apply events to change state ---
    override fun applyEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationSubmittedEvent -> {
                // Initial state is set via constructor for final fields,
                // status is the main mutable part from this event
                this.status = ApplicationStatus.PENDING
            }
            is ApplicationApprovedEvent -> {
                this.status = ApplicationStatus.APPROVED
                this.processedAt = event.approvalTime
                this.processedBy = event.approvedBy
                this.processingNotes = event.notes
            }
            is ApplicationRejectedEvent -> {
                this.status = ApplicationStatus.REJECTED
                this.processedAt = event.rejectionTime
                this.processedBy = event.rejectedBy
                this.processingNotes = event.notes ?: event.reason
            }
            is ApplicationReopenedEvent -> {
                this.status = ApplicationStatus.PENDING
                // Clear previous processing info for a reopened application
                this.processedAt = null
                this.processedBy = null
                this.processingNotes = "Reopened by ${event.reopenedBy.value}: ${event.reasonForReopening}"
            }
        }
    }
}
