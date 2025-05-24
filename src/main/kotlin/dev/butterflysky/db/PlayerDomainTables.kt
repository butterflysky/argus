package dev.butterflysky.db

import dev.butterflysky.domain.* // Imports PlayerId, ApplicationId, ApplicationStatus, MembershipId, MembershipStatus
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp 
import org.jetbrains.exposed.sql.ReferenceOption
import kotlinx.datetime.Clock 
import kotlinx.datetime.Instant 

/**
 * Contains table definitions for the Player-centric domain model.
 */
object PlayerDomainTables {

    /**
     * Table for storing Player entities.
     * The primary key is PlayerId.value (String), which is typically a Discord ID.
     */
    object Players : org.jetbrains.exposed.sql.Table("players") {
        val id = varchar("id", 64) // PlayerId.value (e.g., Discord ID)
        val primaryMinecraftUuid = uuid("primary_minecraft_uuid").nullable()
        val primaryMinecraftUsername = varchar("primary_minecraft_username", 128).nullable()
        val createdAt = timestamp("created_at").default(Clock.System.now()) 
        val updatedAt = timestamp("updated_at").default(Clock.System.now()) 

        override val primaryKey = PrimaryKey(id)
    }

    /**
     * Table for linking multiple Minecraft accounts to a single Player.
     * This represents the Player.linkedMinecraftAccounts map.
     */
    object PlayerMinecraftLinks : org.jetbrains.exposed.sql.Table("player_minecraft_links") {
        val playerId = varchar("player_id", 64).references(Players.id, onDelete = ReferenceOption.CASCADE)
        val minecraftUuid = uuid("minecraft_uuid")
        val minecraftUsername = varchar("minecraft_username", 128)
        // Potentially add 'linkedAt' timestamp if needed

        override val primaryKey = PrimaryKey(playerId, minecraftUuid) // Composite primary key
    }

    /**
     * Table for storing Application entities.
     */
    object Applications : UUIDTable("applications") { // ApplicationId.value (UUID)
        val playerId = varchar("player_id", 64).references(Players.id, onDelete = ReferenceOption.CASCADE)
        val gameAccountId = uuid("game_account_id") // The specific Minecraft UUID for this application
        val minecraftUsername = varchar("minecraft_username", 128) // Username at time of application
        val status = enumerationByName("status", 20, ApplicationStatus::class).default(ApplicationStatus.PENDING)
        val details = text("details")
        val submittedAt = timestamp("submitted_at").default(Clock.System.now()) 
        val processedAt = timestamp("processed_at").nullable()
        val processedBy = varchar("processed_by_player_id", 64).references(Players.id, onDelete = ReferenceOption.SET_NULL).nullable()
        val processingNotes = text("processing_notes").nullable()
    }

    /**
     * Table for storing Membership entities.
     * The primary key is MembershipId.value which is PlayerId.value (String).
     */
    object Memberships : org.jetbrains.exposed.sql.Table("memberships") {
        val id = varchar("id", 64) // MembershipId.value (which is PlayerId.value)
        // playerId column is redundant if id is PlayerId, but kept for explicit FK if desired, or remove if id is directly PlayerId
        // val playerId = varchar("player_id", 64).references(Players.id, onDelete = ReferenceOption.CASCADE)
        val status = enumerationByName("status", 20, MembershipStatus::class)
        val statusReason = text("status_reason").nullable()
        val createdAt = timestamp("created_at").default(Clock.System.now()) 
        val lastStatusChangeAt = timestamp("last_status_change_at").default(Clock.System.now()) 
        val lastStatusChangedBy = varchar("last_status_changed_by_player_id", 64).references(Players.id, onDelete = ReferenceOption.SET_NULL).nullable()

        override val primaryKey = PrimaryKey(id)
        // If using a separate playerId column that must match id:
        // index(true, id, playerId) // Unique index to ensure playerId matches id if both exist
    }
}
