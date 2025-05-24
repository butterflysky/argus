package dev.butterflysky.domain

import dev.butterflysky.domain.events.MembershipCreatedEvent
import dev.butterflysky.domain.events.MembershipEvent
import dev.butterflysky.domain.events.MembershipStatusChangedEvent
import dev.butterflysky.eventsourcing.AbstractAggregateRoot
import java.time.Instant

class Membership private constructor(
    id: MembershipId, // This is the aggregate ID, derived from PlayerId
    val playerId: PlayerId // Store PlayerId explicitly for clarity and direct access
) : AbstractAggregateRoot<MembershipId, MembershipEvent>(id) {

    lateinit var status: MembershipStatus
        private set
    var statusReason: String? = null
        private set
    lateinit var createdAt: Instant
        private set
    lateinit var updatedAt: Instant
        private set
    var updatedBy: PlayerId? = null
        private set

    companion object {
        fun create(
            playerId: PlayerId,
            initialStatus: MembershipStatus = MembershipStatus.PENDING,
            reason: String? = "New membership created",
            actor: PlayerId? = null // Actor initiating creation, currently unused in MembershipCreatedEvent
        ): Membership {
            val membershipId = MembershipId(playerId) // Derive MembershipId from PlayerId
            val membership = Membership(membershipId, playerId)
            membership.recordEvent(
                MembershipCreatedEvent(
                    memId = membershipId,
                    forPlayerId = playerId,
                    initialStatus = initialStatus,
                    reason = reason,
                    createdAt = Instant.now()
                )
            )
            return membership
        }
    }

    private fun changeStatus(newStatus: MembershipStatus, actor: PlayerId?, reason: String?) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Membership state not initialized. Cannot change status.")
        }
        recordEvent(
            MembershipStatusChangedEvent(
                memId = this.id,
                newStatus = newStatus,
                oldStatus = this.status,
                reason = reason,
                changedBy = actor,
                changeTime = Instant.now()
            )
        )
    }

    fun whitelist(actor: PlayerId, reason: String? = null) {
        if (!this::status.isInitialized || (status != MembershipStatus.PENDING && status != MembershipStatus.UNWHITELISTED && status != MembershipStatus.REJECTED)) {
            throw IllegalStateException("Membership must be PENDING, UNWHITELISTED, or REJECTED to be whitelisted. Current status: $status")
        }
        changeStatus(MembershipStatus.WHITELISTED, actor, reason ?: "Whitelisted by admin/moderator")
    }

    fun ban(actor: PlayerId, reason: String) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Membership state not initialized. Cannot ban.")
        }
        if (status == MembershipStatus.BANNED) {
            throw IllegalStateException("Membership is already BANNED.")
        }
        changeStatus(MembershipStatus.BANNED, actor, reason)
    }

    fun unwhitelist(actor: PlayerId, reason: String? = null) {
        if (!this::status.isInitialized || status != MembershipStatus.WHITELISTED) {
            throw IllegalStateException("Membership must be WHITELISTED to be unwhitelisted. Current status: $status")
        }
        changeStatus(MembershipStatus.UNWHITELISTED, actor, reason ?: "Unwhitelisted by admin/moderator")
    }

    fun reject(actor: PlayerId, reason: String? = null) {
        if (!this::status.isInitialized || status != MembershipStatus.PENDING) {
            throw IllegalStateException("Membership must be PENDING to be rejected. Current status: $status")
        }
        changeStatus(MembershipStatus.REJECTED, actor, reason ?: "Membership application rejected")
    }

    fun setPending(actor: PlayerId?, reason: String? = null) {
        if (!this::status.isInitialized) {
            throw IllegalStateException("Membership state not initialized. Cannot set to pending.")
        }
        if (status == MembershipStatus.PENDING) {
            return // No-op if already pending
        }
        if (status != MembershipStatus.REJECTED && status != MembershipStatus.UNWHITELISTED) {
             throw IllegalStateException("Membership can only be set to PENDING from REJECTED or UNWHITELISTED. Current status: $status")
        }
        changeStatus(MembershipStatus.PENDING, actor, reason ?: "Membership set to pending")
    }

    override fun applyEvent(event: MembershipEvent) {
        when (event) {
            is MembershipCreatedEvent -> {
                this.status = event.initialStatus
                this.statusReason = event.reason
                this.createdAt = event.createdAt
                this.updatedAt = event.createdAt
                // Note: updatedBy for creation is not set from this event currently
            }
            is MembershipStatusChangedEvent -> {
                this.status = event.newStatus
                this.statusReason = event.reason
                this.updatedAt = event.changeTime
                this.updatedBy = event.changedBy
            }
        }
    }
}