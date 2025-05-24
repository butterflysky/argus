package dev.butterflysky.domain.events

import dev.butterflysky.domain.ApplicationId
import dev.butterflysky.domain.ApplicationStatus // Required for ApplicationApprovedEvent, etc.
import dev.butterflysky.domain.PlayerId
import dev.butterflysky.eventsourcing.AbstractDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Base sealed class for all events related to the Application aggregate.
 */
sealed class ApplicationEvent(
    applicationId: ApplicationId,
    override val eventType: String
) : AbstractDomainEvent<ApplicationId>(applicationId, "Application", eventType)

/**
 * Event indicating a new whitelist application was submitted.
 */
data class ApplicationSubmittedEvent(
    val appId: ApplicationId,
    val submittedByPlayerId: PlayerId,
    val forGameAccountId: UUID,
    val forMinecraftUsername: String,
    val applicationDetails: String,
    val submissionTime: Instant = Instant.now()
) : ApplicationEvent(appId, "ApplicationSubmitted")

/**
 * Event indicating an application was approved.
 */
data class ApplicationApprovedEvent(
    val appId: ApplicationId,
    val approvedBy: PlayerId, // PlayerId of the moderator or system
    val approvalTime: Instant = Instant.now(),
    val notes: String?
) : ApplicationEvent(appId, "ApplicationApproved")

/**
 * Event indicating an application was rejected.
 */
data class ApplicationRejectedEvent(
    val appId: ApplicationId,
    val rejectedBy: PlayerId, // PlayerId of the moderator or system
    val rejectionTime: Instant = Instant.now(),
    val reason: String,
    val notes: String?
) : ApplicationEvent(appId, "ApplicationRejected")

/**
 * Event indicating a previously rejected application was reopened.
 */
data class ApplicationReopenedEvent(
    val appId: ApplicationId,
    val reopenedBy: PlayerId, // PlayerId of the moderator or system
    val reopeningTime: Instant = Instant.now(),
    val reasonForReopening: String
) : ApplicationEvent(appId, "ApplicationReopened")
