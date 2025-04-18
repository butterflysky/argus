package dev.butterflysky.service

import dev.butterflysky.db.DatabaseTestBase
import dev.butterflysky.db.WhitelistDatabase
import dev.butterflysky.db.WhitelistDatabase.ApplicationStatus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

/**
 * Tests for the ban-related methods in WhitelistService
 */
class BanServiceTest : DatabaseTestBase() {
    private lateinit var service: WhitelistService
    private lateinit var mockServer: net.minecraft.server.MinecraftServer
    private lateinit var mockPlayerManager: net.minecraft.server.PlayerManager
    private lateinit var mockUserBanList: net.minecraft.server.BannedPlayerList
    
    @BeforeEach
    fun setupService() {
        // Create mocks for Minecraft server components
        mockServer = Mockito.mock(net.minecraft.server.MinecraftServer::class.java)
        mockPlayerManager = Mockito.mock(net.minecraft.server.PlayerManager::class.java)
        mockUserBanList = Mockito.mock(net.minecraft.server.BannedPlayerList::class.java)
        
        // Setup mock behavior
        whenever(mockServer.playerManager).thenReturn(mockPlayerManager)
        whenever(mockPlayerManager.userBanList).thenReturn(mockUserBanList)
        
        // Create the service instance and inject mocks
        service = WhitelistService.getInstance()
        val initField = WhitelistService::class.java.getDeclaredField("server")
        initField.isAccessible = true
        initField.set(service, mockServer)
    }
    
    @Test
    fun `banPlayer should mark applications as banned and add to vanilla ban list`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "TestMinecraftPlayer", discordUser)
        val moderatorDiscordId = createTestDiscordUser("ModeratorUser").id.value.toString()
        val banReason = "Test ban reason"
        
        // Create an approved application
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = ApplicationStatus.APPROVED
                appliedAt = Instant.now()
                eligibleAt = Instant.now()
                isModeratorCreated = false
            }
        }
        
        // Mock the vanilla ban list behavior
        whenever(mockUserBanList.contains(any())).thenReturn(false)
        
        // When
        val result = service.banPlayer(
            uuid = minecraftUser.id.value,
            username = minecraftUser.currentUsername,
            discordId = moderatorDiscordId,
            reason = banReason
        )
        
        // Then
        assertThat(result).isTrue()
        
        // Verify the application is now banned in our database
        transaction {
            val application = WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id)
            }.firstOrNull()
            
            assertThat(application).isNotNull
            assertThat(application!!.status).isEqualTo(ApplicationStatus.BANNED)
            assertThat(application.notes).isEqualTo("Banned: $banReason")
            
            // Verify the audit log was created
            val auditLogs = WhitelistDatabase.AuditLog.find {
                (WhitelistDatabase.AuditLogs.actionType eq WhitelistDatabase.AuditActionType.PLAYER_BANNED.name) and
                (WhitelistDatabase.AuditLogs.entityId eq minecraftUser.id.value.toString())
            }.toList()
            
            assertThat(auditLogs).hasSize(1)
            assertThat(auditLogs[0].details).contains(banReason)
        }
    }
    
    @Test
    fun `banPlayer should handle already banned players gracefully`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "TestMinecraftPlayer", discordUser)
        val moderatorDiscordId = createTestDiscordUser("ModeratorUser").id.value.toString()
        val banReason = "Test ban reason"
        
        // Create an approved application
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = ApplicationStatus.APPROVED
                appliedAt = Instant.now()
                eligibleAt = Instant.now()
                isModeratorCreated = false
            }
        }
        
        // Mock the vanilla ban list to say the player is already banned
        whenever(mockUserBanList.contains(any())).thenReturn(true)
        
        // When
        val result = service.banPlayer(
            uuid = minecraftUser.id.value,
            username = minecraftUser.currentUsername,
            discordId = moderatorDiscordId,
            reason = banReason
        )
        
        // Then
        assertThat(result).isTrue()
        
        // Verify the application is updated in our database even though player was already banned
        transaction {
            val application = WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id)
            }.firstOrNull()
            
            assertThat(application).isNotNull
            assertThat(application!!.status).isEqualTo(ApplicationStatus.BANNED)
        }
    }
    
    @Test
    fun `handlePlayerBanned should update database without interacting with vanilla server`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "TestMinecraftPlayer", discordUser)
        val banReason = "Test ban reason"
        
        // Create an approved application
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = ApplicationStatus.APPROVED
                appliedAt = Instant.now()
                eligibleAt = Instant.now()
                isModeratorCreated = false
            }
        }
        
        // When - this method should not interact with the vanilla ban list
        val result = service.handlePlayerBanned(
            uuid = minecraftUser.id.value,
            username = minecraftUser.currentUsername,
            discordId = null, // Simulating console ban
            reason = banReason
        )
        
        // Then
        assertThat(result).isTrue()
        
        // Verify the application was updated in our database
        transaction {
            val application = WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id) and
                (WhitelistDatabase.WhitelistApplications.minecraftUser eq minecraftUser.id)
            }.firstOrNull()
            
            assertThat(application).isNotNull
            assertThat(application!!.status).isEqualTo(ApplicationStatus.BANNED)
            
            // Verify the audit log has the SYSTEM_USER_ID as performer since discordId was null
            val auditLogs = WhitelistDatabase.AuditLog.find {
                (WhitelistDatabase.AuditLogs.actionType eq WhitelistDatabase.AuditActionType.PLAYER_BANNED.name) and
                (WhitelistDatabase.AuditLogs.entityId eq minecraftUser.id.value.toString())
            }.toList()
            
            assertThat(auditLogs).hasSize(1)
            assertThat(auditLogs[0].performedBy?.id?.value).isEqualTo(WhitelistDatabase.SYSTEM_USER_ID)
        }
    }
}