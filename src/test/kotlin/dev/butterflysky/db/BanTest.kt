package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Tests for the ban-related database functionality
 */
class BanTest : DatabaseTestBase() {
    
    @Test
    fun `should mark application as banned when player is banned`() {
        // Given
        val discordUser = createTestDiscordUser("ApplicantUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "MinecraftPlayer", discordUser)
        
        // Create an approved application
        val application = transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = moderator
            }
        }
        
        // When - Ban the player
        val banReason = "Test ban reason"
        transaction {
            // Simulate the ban process
            application.status = WhitelistDatabase.ApplicationStatus.BANNED
            application.processedAt = Clock.System.now()
            application.processedBy = moderator
            application.notes = "Banned: $banReason"
            
            // Create audit log
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.PLAYER_BANNED,
                entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                entityId = minecraftUser.id.value.toString(),
                performedBy = moderator,
                details = "Banned player ${minecraftUser.currentUsername}. Reason: $banReason"
            )
        }
        
        // Then
        transaction {
            // Application should now be marked as banned
            val updatedApplication = WhitelistDatabase.WhitelistApplication.findById(application.id)
            assertThat(updatedApplication).isNotNull
            assertThat(updatedApplication!!.status).isEqualTo(WhitelistDatabase.ApplicationStatus.BANNED)
            assertThat(updatedApplication.notes).isEqualTo("Banned: $banReason")
            
            // Should have created an audit log entry
            val auditLogs = WhitelistDatabase.AuditLog.find {
                (WhitelistDatabase.AuditLogs.actionType eq WhitelistDatabase.AuditActionType.PLAYER_BANNED.name) and
                (WhitelistDatabase.AuditLogs.entityId eq minecraftUser.id.value.toString())
            }.toList()
            
            assertThat(auditLogs).hasSize(1)
            assertThat(auditLogs[0].performedBy?.id?.value).isEqualTo(moderator.id.value)
            assertThat(auditLogs[0].details).contains(banReason)
        }
    }
    
    @Test
    fun `should find banned applications by status`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser1 = createTestMinecraftUser(UUID.randomUUID(), "MCUser1", discordUser)
        val minecraftUser2 = createTestMinecraftUser(UUID.randomUUID(), "MCUser2", discordUser)
        
        // Create applications with different statuses
        transaction {
            // Regular approved application
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser1
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = moderator
            }
            
            // Banned application
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser2
                status = WhitelistDatabase.ApplicationStatus.BANNED
                appliedAt = Clock.System.now() // Player might have applied before ban
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now() // Ban processing time
                processedBy = moderator
                notes = "Banned: Test ban reason"
            }
        }
        
        // When - properly constrain our queries to only find applications for the specific Discord user
        val approvedApplications = transaction {
            // Only find approved applications for our specific Discord user
            WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.status eq WhitelistDatabase.ApplicationStatus.APPROVED) and
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser1.id)
            }.toList()
        }
        
        val bannedApplications = transaction {
            // Only find banned applications for our specific Discord user
            WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.status eq WhitelistDatabase.ApplicationStatus.BANNED) and
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser2.id)
            }.toList()
        }
        
        // Then
        assertThat(approvedApplications).hasSize(1)
        assertThat(bannedApplications).hasSize(1)
        
        transaction {
            assertThat(approvedApplications[0].minecraftUser.id.value).isEqualTo(minecraftUser1.id.value)
            assertThat(bannedApplications[0].minecraftUser.id.value).isEqualTo(minecraftUser2.id.value)
            assertThat(bannedApplications[0].notes).startsWith("Banned:")
        }
    }
    
    @Test
    fun `should create player_banned audit log entry`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "BannedPlayer", discordUser)
        val banReason = "Test ban reason"
        
        // When
        val auditLog = transaction {
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.PLAYER_BANNED,
                entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                entityId = minecraftUser.id.value.toString(),
                performedBy = moderator,
                details = "Banned player ${minecraftUser.currentUsername}. Reason: $banReason"
            )
        }
        
        // Then
        transaction {
            assertThat(auditLog.id.value).isGreaterThan(0)
            assertThat(auditLog.actionType).isEqualTo(WhitelistDatabase.AuditActionType.PLAYER_BANNED.name)
            assertThat(auditLog.entityType).isEqualTo(WhitelistDatabase.EntityType.MINECRAFT_USER.name)
            assertThat(auditLog.entityId).isEqualTo(minecraftUser.id.value.toString())
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(moderator.id.value)
            assertThat(auditLog.details).contains(banReason)
            assertThat(auditLog.details).contains(minecraftUser.currentUsername)
        }
    }
    
    @Test
    fun `should create player_unbanned audit log entry`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "UnbannedPlayer", discordUser)
        
        // When
        val auditLog = transaction {
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.PLAYER_UNBANNED,
                entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                entityId = minecraftUser.id.value.toString(),
                performedBy = moderator,
                details = "Unbanned player ${minecraftUser.currentUsername}"
            )
        }
        
        // Then
        transaction {
            assertThat(auditLog.id.value).isGreaterThan(0)
            assertThat(auditLog.actionType).isEqualTo(WhitelistDatabase.AuditActionType.PLAYER_UNBANNED.name)
            assertThat(auditLog.entityType).isEqualTo(WhitelistDatabase.EntityType.MINECRAFT_USER.name)
            assertThat(auditLog.entityId).isEqualTo(minecraftUser.id.value.toString())
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(moderator.id.value)
            assertThat(auditLog.details).contains("Unbanned")
            assertThat(auditLog.details).contains(minecraftUser.currentUsername)
        }
    }
}