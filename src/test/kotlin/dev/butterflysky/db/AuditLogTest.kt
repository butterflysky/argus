package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Tests for the AuditLog entity and related functionality
 */
class AuditLogTest : DatabaseTestBase() {
    
    @Test
    fun `should create audit log entry successfully`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD
        val entityType = WhitelistDatabase.EntityType.DISCORD_USER
        val entityId = discordUser.id.value.toString()
        val details = "Test audit log details"
        
        // When
        val auditLog = transaction {
            WhitelistDatabase.createAuditLog(
                actionType = actionType,
                entityType = entityType,
                entityId = entityId,
                performedBy = discordUser,
                details = details
            )
        }
        
        // Then
        transaction {
            assertThat(auditLog.id.value).isGreaterThan(0)
            assertThat(auditLog.actionType).isEqualTo(actionType.name)
            assertThat(auditLog.entityType).isEqualTo(entityType.name)
            assertThat(auditLog.entityId).isEqualTo(entityId)
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(discordUser.id.value)
            assertThat(auditLog.details).isEqualTo(details)
            
            // Verify timestamp is recent
            val now = Instant.now()
            assertThat(auditLog.performedAt).isBetween(
                now.minus(5, ChronoUnit.SECONDS),
                now.plus(5, ChronoUnit.SECONDS)
            )
        }
    }
    
    @Test
    fun `should create audit log with system user when performedBy is null`() {
        // Given
        val actionType = WhitelistDatabase.AuditActionType.LEGACY_IMPORT
        val entityType = WhitelistDatabase.EntityType.MINECRAFT_USER
        val entityId = UUID.randomUUID().toString()
        val details = "System-generated audit log"
        
        // When
        val auditLog = transaction {
            WhitelistDatabase.createAuditLog(
                actionType = actionType,
                entityType = entityType,
                entityId = entityId,
                performedBy = null, // No performer specified
                details = details
            )
        }
        
        // Then
        transaction {
            assertThat(auditLog.id.value).isGreaterThan(0)
            assertThat(auditLog.actionType).isEqualTo(actionType.name)
            assertThat(auditLog.entityType).isEqualTo(entityType.name)
            assertThat(auditLog.entityId).isEqualTo(entityId)
            
            // Should use system user
            assertThat(auditLog.performedBy).isNotNull()
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(WhitelistDatabase.SYSTEM_USER_ID)
            
            assertThat(auditLog.details).isEqualTo(details)
        }
    }
    
    @Test
    fun `should create audit log with explicit entity name`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD
        val entityType = WhitelistDatabase.EntityType.MINECRAFT_USER
        val entityId = UUID.randomUUID().toString()
        val details = "Test audit log with explicit entity name"
        val entityName = "ExplicitEntityName"
        
        // When
        val auditLog = transaction {
            WhitelistDatabase.createAuditLog(
                actionType = actionType,
                entityType = entityType,
                entityId = entityId,
                performedBy = discordUser,
                details = details,
                entityName = entityName
            )
        }
        
        // Then
        transaction {
            assertThat(auditLog.id.value).isGreaterThan(0)
            assertThat(auditLog.actionType).isEqualTo(actionType.name)
            assertThat(auditLog.entityType).isEqualTo(entityType.name)
            assertThat(auditLog.entityId).isEqualTo(entityId)
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(discordUser.id.value)
            assertThat(auditLog.details).isEqualTo(details)
        }
    }
    
    @Test
    fun `should handle user left event with audit logging`() {
        // Given
        val discordUser = createTestDiscordUser("LeavingUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        
        // When
        val leftUser = transaction {
            WhitelistDatabase.handleUserLeft(
                discordId = discordUser.id.value,
                performedBy = moderator
            )
        }
        
        // Then
        assertThat(leftUser).isNotNull
        
        transaction {
            // Verify the user is marked as left
            assertThat(leftUser!!.isInServer).isFalse()
            assertThat(leftUser.leftServerAt).isNotNull()
            
            // Verify an audit log was created
            val auditLog = WhitelistDatabase.AuditLog.find { 
                WhitelistDatabase.AuditLogs.entityId eq discordUser.id.value.toString()
            }.firstOrNull()
            
            assertThat(auditLog).isNotNull
            assertThat(auditLog!!.actionType).isEqualTo(WhitelistDatabase.AuditActionType.USER_LEFT.name)
            assertThat(auditLog.entityType).isEqualTo(WhitelistDatabase.EntityType.DISCORD_USER.name)
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(moderator.id.value)
        }
    }
    
    @Test
    fun `should handle user left event with system performer when none specified`() {
        // Given
        val discordUser = createTestDiscordUser("LeavingUser")
        
        // When
        val leftUser = transaction {
            WhitelistDatabase.handleUserLeft(
                discordId = discordUser.id.value,
                performedBy = null // No performer specified
            )
        }
        
        // Then
        assertThat(leftUser).isNotNull
        
        transaction {
            // Verify the user is marked as left
            assertThat(leftUser!!.isInServer).isFalse()
            
            // Verify an audit log was created with system user
            val auditLog = WhitelistDatabase.AuditLog.find { 
                WhitelistDatabase.AuditLogs.entityId eq discordUser.id.value.toString()
            }.firstOrNull()
            
            assertThat(auditLog).isNotNull
            assertThat(auditLog!!.actionType).isEqualTo(WhitelistDatabase.AuditActionType.USER_LEFT.name)
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(WhitelistDatabase.SYSTEM_USER_ID)
        }
    }
    
    @Test
    fun `should return null from handleUserLeft when user does not exist`() {
        // Given
        val nonExistentUserId = 999999L
        
        // When
        val result = transaction {
            WhitelistDatabase.handleUserLeft(
                discordId = nonExistentUserId,
                performedBy = null
            )
        }
        
        // Then
        assertThat(result).isNull()
    }
    
    @Test
    fun `should query audit logs by entity ID`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val entityId = discordUser.id.value.toString()
        
        // Create multiple audit logs for the same entity
        transaction {
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD,
                entityType = WhitelistDatabase.EntityType.DISCORD_USER,
                entityId = entityId,
                performedBy = moderator,
                details = "First action"
            )
            
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.WHITELIST_REMOVE,
                entityType = WhitelistDatabase.EntityType.DISCORD_USER,
                entityId = entityId,
                performedBy = moderator,
                details = "Second action"
            )
        }
        
        // When
        val logs = transaction {
            WhitelistDatabase.AuditLog.find {
                WhitelistDatabase.AuditLogs.entityId eq entityId
            }.toList()
        }
        
        // Then
        assertThat(logs).hasSize(2)
        transaction {
            assertThat(logs.map { it.actionType }).containsExactlyInAnyOrder(
                WhitelistDatabase.AuditActionType.WHITELIST_ADD.name,
                WhitelistDatabase.AuditActionType.WHITELIST_REMOVE.name
            )
            
            // All should reference the same entity ID
            logs.forEach { log ->
                assertThat(log.entityId).isEqualTo(entityId)
                assertThat(log.performedBy?.id?.value).isEqualTo(moderator.id.value)
            }
        }
    }
    
    @Test
    fun `should query audit logs by action type`() {
        // Given
        val moderator = createTestDiscordUser("ModeratorUser")
        val actionType = WhitelistDatabase.AuditActionType.ACCOUNT_TRANSFER
        
        // Create multiple audit logs with different action types
        transaction {
            // Create log with the target action type
            WhitelistDatabase.createAuditLog(
                actionType = actionType,
                entityType = WhitelistDatabase.EntityType.MINECRAFT_USER,
                entityId = UUID.randomUUID().toString(),
                performedBy = moderator,
                details = "Target action"
            )
            
            // Create log with a different action type
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD,
                entityType = WhitelistDatabase.EntityType.WHITELIST_APPLICATION,
                entityId = "100",
                performedBy = moderator,
                details = "Different action"
            )
        }
        
        // When
        val logs = transaction {
            WhitelistDatabase.AuditLog.find {
                WhitelistDatabase.AuditLogs.actionType eq actionType.name
            }.toList()
        }
        
        // Then
        assertThat(logs).hasSize(1)
        transaction {
            assertThat(logs[0].actionType).isEqualTo(actionType.name)
            assertThat(logs[0].entityType).isEqualTo(WhitelistDatabase.EntityType.MINECRAFT_USER.name)
            assertThat(logs[0].details).isEqualTo("Target action")
        }
    }
    
    @Test
    fun `should query audit logs by performer`() {
        // Given
        val moderator1 = createTestDiscordUser("Moderator1")
        val moderator2 = createTestDiscordUser("Moderator2")
        
        // Create audit logs with different performers
        transaction {
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.WHITELIST_ADD,
                entityType = WhitelistDatabase.EntityType.DISCORD_USER,
                entityId = "user1",
                performedBy = moderator1,
                details = "Action by moderator 1"
            )
            
            WhitelistDatabase.createAuditLog(
                actionType = WhitelistDatabase.AuditActionType.WHITELIST_REMOVE,
                entityType = WhitelistDatabase.EntityType.DISCORD_USER,
                entityId = "user2",
                performedBy = moderator2,
                details = "Action by moderator 2"
            )
        }
        
        // When
        val logs = transaction {
            WhitelistDatabase.AuditLog.find {
                WhitelistDatabase.AuditLogs.performedBy eq moderator1.id
            }.toList()
        }
        
        // Then
        assertThat(logs).hasSize(1)
        transaction {
            assertThat(logs[0].performedBy?.id?.value).isEqualTo(moderator1.id.value)
            assertThat(logs[0].details).isEqualTo("Action by moderator 1")
        }
    }
}