package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Tests for the MinecraftUser entity and related functionality
 */
class MinecraftUserTest : DatabaseTestBase() {
    
    @Test
    fun `should create a Minecraft user successfully`() {
        // Given
        val uuid = UUID.randomUUID()
        val username = "TestMinecraftUser"
        
        // When
        val user = createTestMinecraftUser(uuid, username)
        
        // Then
        transaction {
            assertThat(user.id.value).isEqualTo(uuid)
            assertThat(user.currentUsername).isEqualTo(username)
            assertThat(user.currentOwner).isNull()
            assertThat(user.transferredAt).isNull()
            assertThat(user.createdAt).isNotNull()
        }
    }
    
    @Test
    fun `should create a Minecraft user with owner`() {
        // Given
        val discordUser = createTestDiscordUser("DiscordOwner")
        val uuid = UUID.randomUUID()
        val username = "TestMinecraftUser"
        
        // When
        val user = createTestMinecraftUser(uuid, username, discordUser)
        
        // Then
        transaction {
            assertThat(user.id.value).isEqualTo(uuid)
            assertThat(user.currentUsername).isEqualTo(username)
            assertThat(user.currentOwner).isNotNull
            assertThat(user.currentOwner?.id?.value).isEqualTo(discordUser.id.value)
        }
    }
    
    @Test
    fun `should retrieve an existing Minecraft user`() {
        // Given
        val uuid = UUID.randomUUID()
        val username = "TestMinecraftUser"
        createTestMinecraftUser(uuid, username)
        
        // When
        val retrievedUser = transaction {
            WhitelistDatabase.MinecraftUser.findById(uuid)
        }
        
        // Then
        assertThat(retrievedUser).isNotNull
        transaction {
            assertThat(retrievedUser!!.currentUsername).isEqualTo(username)
        }
    }
    
    @Test
    fun `should transfer Minecraft user to a new owner`() {
        // Given
        val originalOwner = createTestDiscordUser("OriginalOwner")
        val newOwner = createTestDiscordUser("NewOwner")
        val uuid = UUID.randomUUID()
        val minecraftUser = createTestMinecraftUser(uuid, "TestMinecraftUser", originalOwner)
        
        // When
        val transferredUser = transaction {
            WhitelistDatabase.transferMinecraftUser(
                minecraftUuid = uuid,
                newOwnerId = newOwner.id.value,
                performedBy = WhitelistDatabase.DiscordUser.getSystemUser(),
                reason = "Test transfer"
            )
        }
        
        // Then
        assertThat(transferredUser).isNotNull
        transaction {
            assertThat(transferredUser!!.currentOwner?.id?.value).isEqualTo(newOwner.id.value)
            assertThat(transferredUser.transferredAt).isNotNull
            
            // Verify transfer time is recent
            val now = Instant.now()
            assertThat(transferredUser.transferredAt).isBetween(
                now.minus(5, ChronoUnit.SECONDS),
                now.plus(5, ChronoUnit.SECONDS)
            )
        }
    }
    
    @Test
    fun `should mark existing applications as removed when transferring ownership`() {
        // Given
        val originalOwner = createTestDiscordUser("OriginalOwner")
        val newOwner = createTestDiscordUser("NewOwner")
        val uuid = UUID.randomUUID()
        val minecraftUser = createTestMinecraftUser(uuid, "TestMinecraftUser", originalOwner)
        
        // Create an approved whitelist application
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                discordUser = originalOwner
                this.minecraftUser = minecraftUser
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Instant.now()
                eligibleAt = Instant.now()
                isModeratorCreated = false
                processedAt = Instant.now()
                processedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
        }
        
        // When
        transaction {
            WhitelistDatabase.transferMinecraftUser(
                minecraftUuid = uuid,
                newOwnerId = newOwner.id.value,
                performedBy = WhitelistDatabase.DiscordUser.getSystemUser(),
                reason = "Test transfer"
            )
        }
        
        // Then
        transaction {
            // Find all applications for this minecraft user
            val applications = WhitelistDatabase.WhitelistApplication.find {
                WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id
            }.toList()
            
            assertThat(applications).isNotEmpty
            applications.forEach { application ->
                assertThat(application.status).isEqualTo(WhitelistDatabase.ApplicationStatus.REMOVED)
            }
        }
    }
    
    @Test
    fun `should not transfer ownership if new owner doesn't exist`() {
        // Given
        val originalOwner = createTestDiscordUser("OriginalOwner")
        val nonExistentOwnerId = 999999L
        val uuid = UUID.randomUUID()
        val minecraftUser = createTestMinecraftUser(uuid, "TestMinecraftUser", originalOwner)
        
        // When
        val result = transaction {
            WhitelistDatabase.transferMinecraftUser(
                minecraftUuid = uuid,
                newOwnerId = nonExistentOwnerId,
                performedBy = WhitelistDatabase.DiscordUser.getSystemUser(),
                reason = "Test transfer"
            )
        }
        
        // Then
        assertThat(result).isNull()
        
        // Verify original ownership remains
        transaction {
            val unchangedUser = WhitelistDatabase.MinecraftUser.findById(uuid)
            assertThat(unchangedUser).isNotNull
            assertThat(unchangedUser!!.currentOwner?.id?.value).isEqualTo(originalOwner.id.value)
        }
    }
    
    @Test
    fun `should create an audit log entry for account transfer`() {
        // Given
        val originalOwner = createTestDiscordUser("OriginalOwner")
        val newOwner = createTestDiscordUser("NewOwner")
        val systemUser = transaction { WhitelistDatabase.DiscordUser.getSystemUser() }
        val uuid = UUID.randomUUID()
        val minecraftUser = createTestMinecraftUser(uuid, "TestMinecraftUser", originalOwner)
        val transferReason = "Test transfer reason"
        
        // When
        transaction {
            WhitelistDatabase.transferMinecraftUser(
                minecraftUuid = uuid,
                newOwnerId = newOwner.id.value,
                performedBy = systemUser,
                reason = transferReason
            )
        }
        
        // Then
        transaction {
            // Find the audit log entry for this transfer
            val auditLog = WhitelistDatabase.AuditLog.find { 
                WhitelistDatabase.AuditLogs.entityId eq uuid.toString()
            }.firstOrNull()
            
            assertThat(auditLog).isNotNull
            assertThat(auditLog!!.actionType).isEqualTo(WhitelistDatabase.AuditActionType.ACCOUNT_TRANSFER.name)
            assertThat(auditLog.entityType).isEqualTo(WhitelistDatabase.EntityType.MINECRAFT_USER.name)
            assertThat(auditLog.performedBy?.id?.value).isEqualTo(systemUser.id.value)
            assertThat(auditLog.details).contains(transferReason)
            assertThat(auditLog.details).contains(originalOwner.currentUsername)
            assertThat(auditLog.details).contains(newOwner.currentUsername)
        }
    }
    
    @Test
    fun `should import legacy Minecraft user successfully`() {
        // Given
        val uuid = UUID.randomUUID()
        val username = "LegacyMinecraftUser"
        val systemUser = transaction { WhitelistDatabase.DiscordUser.getSystemUser() }
        
        // When
        val result = transaction {
            WhitelistDatabase.importLegacyMinecraftUser(
                minecraftUuid = uuid,
                username = username,
                performedBy = systemUser
            )
        }
        
        // Then
        assertThat(result).isNotNull
        
        val (minecraftUser, application) = result!!
        
        transaction {
            // Verify Minecraft user
            assertThat(minecraftUser.id.value).isEqualTo(uuid)
            assertThat(minecraftUser.currentUsername).isEqualTo(username)
            assertThat(minecraftUser.currentOwner?.id?.value).isEqualTo(WhitelistDatabase.UNMAPPED_DISCORD_ID)
            
            // Verify whitelist application
            assertThat(application.discordUser.id.value).isEqualTo(WhitelistDatabase.UNMAPPED_DISCORD_ID)
            assertThat(application.minecraftUser.id.value).isEqualTo(uuid)
            assertThat(application.status).isEqualTo(WhitelistDatabase.ApplicationStatus.APPROVED)
            assertThat(application.isModeratorCreated).isTrue()
            assertThat(application.overrideReason).isEqualTo("Legacy whitelist import")
            
            // Verify username history was created
            val usernameHistory = minecraftUser.usernameHistory.toList()
            assertThat(usernameHistory).hasSize(1)
            assertThat(usernameHistory[0].username).isEqualTo(username)
            
            // Verify audit log was created
            val auditLog = WhitelistDatabase.AuditLog.find { 
                WhitelistDatabase.AuditLogs.entityId eq uuid.toString() 
            }.firstOrNull()
            
            assertThat(auditLog).isNotNull
            assertThat(auditLog!!.actionType).isEqualTo(WhitelistDatabase.AuditActionType.LEGACY_IMPORT.name)
            assertThat(auditLog.entityType).isEqualTo(WhitelistDatabase.EntityType.MINECRAFT_USER.name)
        }
    }
    
    @Test
    fun `should not import legacy Minecraft user if already exists`() {
        // Given - Create the user first
        val uuid = UUID.randomUUID()
        val username = "ExistingUser"
        val systemUser = transaction { WhitelistDatabase.DiscordUser.getSystemUser() }
        
        createTestMinecraftUser(uuid, username)
        
        // When - Try to import the same user
        val result = transaction {
            WhitelistDatabase.importLegacyMinecraftUser(
                minecraftUuid = uuid,
                username = username,
                performedBy = systemUser
            )
        }
        
        // Then
        assertThat(result).isNull()
    }
}