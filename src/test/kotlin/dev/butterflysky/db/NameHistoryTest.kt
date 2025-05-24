package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds
import java.util.UUID

/**
 * Tests for the name history tracking functionality for both Discord and Minecraft users
 */
class NameHistoryTest : DatabaseTestBase() {
    
    @Test
    fun `should record Discord username history`() {
        // Given
        val discordUser = createTestDiscordUser("OriginalUsername")
        val recorder = createTestDiscordUser("RecorderUser")
        val newUsername = "UpdatedUsername"
        
        // When
        val nameHistoryEntry = transaction {
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = newUsername
                type = WhitelistDatabase.NameType.USERNAME
                recordedBy = recorder
            }
        }
        
        // Then
        transaction {
            assertThat(nameHistoryEntry.id.value).isGreaterThan(0)
            assertThat(nameHistoryEntry.discordUser.id.value).isEqualTo(discordUser.id.value)
            assertThat(nameHistoryEntry.username).isEqualTo(newUsername)
            assertThat(nameHistoryEntry.type).isEqualTo(WhitelistDatabase.NameType.USERNAME)
            assertThat(nameHistoryEntry.recordedBy?.id?.value).isEqualTo(recorder.id.value)
            
            // Verify timestamp is recent
            val now = Clock.System.now()
            val fiveSeconds = 5.seconds
            assertThat(nameHistoryEntry.recordedAt).isGreaterThanOrEqualTo(now - fiveSeconds)
            assertThat(nameHistoryEntry.recordedAt).isLessThanOrEqualTo(now + fiveSeconds)
            
            // Verify we can retrieve the history from the user
            val nameHistory = discordUser.nameHistory.toList()
            assertThat(nameHistory).hasSize(1)
            assertThat(nameHistory[0].username).isEqualTo(newUsername)
        }
    }
    
    @Test
    fun `should record Discord servername history`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val servername = "ServerNickname"
        
        // When
        val nameHistoryEntry = transaction {
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = servername
                type = WhitelistDatabase.NameType.SERVERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser() // System recorded
            }
        }
        
        // Then
        transaction {
            assertThat(nameHistoryEntry.id.value).isGreaterThan(0)
            assertThat(nameHistoryEntry.discordUser.id.value).isEqualTo(discordUser.id.value)
            assertThat(nameHistoryEntry.username).isEqualTo(servername)
            assertThat(nameHistoryEntry.type).isEqualTo(WhitelistDatabase.NameType.SERVERNAME)
            assertThat(nameHistoryEntry.recordedBy?.id?.value).isEqualTo(WhitelistDatabase.SYSTEM_USER_ID)
            
            // Verify we can retrieve the history from the user
            val nameHistory = discordUser.nameHistory.toList()
            assertThat(nameHistory).hasSize(1)
        }
    }
    
    @Test
    fun `should record multiple name history entries for a Discord user`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        
        // When
        transaction {
            // Add username change
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = "Username1"
                type = WhitelistDatabase.NameType.USERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
            
            // Add servername change
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = "Servername1"
                type = WhitelistDatabase.NameType.SERVERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
            
            // Add another username change
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = "Username2"
                type = WhitelistDatabase.NameType.USERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
        }
        
        // Then
        transaction {
            val nameHistory = discordUser.nameHistory.toList()
            assertThat(nameHistory).hasSize(3)
            
            // Verify we can filter by type
            val usernameHistory = nameHistory.filter { it.type == WhitelistDatabase.NameType.USERNAME }
            val servernameHistory = nameHistory.filter { it.type == WhitelistDatabase.NameType.SERVERNAME }
            
            assertThat(usernameHistory).hasSize(2)
            assertThat(servernameHistory).hasSize(1)
            
            // Verify chronological ordering
            assertThat(usernameHistory.map { it.username }).containsExactly("Username1", "Username2")
        }
    }
    
    @Test
    fun `should record Minecraft username history`() {
        // Given
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "OriginalMCUsername")
        val recorder = createTestDiscordUser("RecorderUser")
        val newUsername = "UpdatedMCUsername"
        
        // When
        val nameHistoryEntry = transaction {
            WhitelistDatabase.MinecraftUserName.new {
                this.minecraftUser = minecraftUser
                username = newUsername
                recordedBy = recorder
            }
        }
        
        // Then
        transaction {
            assertThat(nameHistoryEntry.id.value).isGreaterThan(0)
            assertThat(nameHistoryEntry.minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(nameHistoryEntry.username).isEqualTo(newUsername)
            assertThat(nameHistoryEntry.recordedBy?.id?.value).isEqualTo(recorder.id.value)
            
            // Verify timestamp is recent
            val now = Clock.System.now()
            val fiveSeconds = 5.seconds
            assertThat(nameHistoryEntry.recordedAt).isGreaterThanOrEqualTo(now - fiveSeconds)
            assertThat(nameHistoryEntry.recordedAt).isLessThanOrEqualTo(now + fiveSeconds)
            
            // Verify we can retrieve the history from the user
            val nameHistory = minecraftUser.usernameHistory.toList()
            assertThat(nameHistory).hasSize(1)
            assertThat(nameHistory[0].username).isEqualTo(newUsername)
        }
    }
    
    @Test
    fun `should record multiple name history entries for a Minecraft user`() {
        // Given
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "OriginalMCUsername")
        
        // When
        transaction {
            // Add first name change
            WhitelistDatabase.MinecraftUserName.new {
                this.minecraftUser = minecraftUser
                username = "MCUsername1"
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
            
            // Add second name change
            WhitelistDatabase.MinecraftUserName.new {
                this.minecraftUser = minecraftUser
                username = "MCUsername2"
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
        }
        
        // Then
        transaction {
            val nameHistory = minecraftUser.usernameHistory.toList()
            assertThat(nameHistory).hasSize(2)
            
            // Sort by recorded time to verify chronological order
            val sortedHistory = nameHistory.sortedBy { it.recordedAt }
            assertThat(sortedHistory.map { it.username }).containsExactly("MCUsername1", "MCUsername2")
        }
    }
    
    @Test
    fun `should cascade delete username history when user is deleted`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        
        // Add name history entries
        transaction {
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = "HistoryEntry1"
                type = WhitelistDatabase.NameType.USERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
            
            WhitelistDatabase.DiscordUserName.new {
                this.discordUser = discordUser
                username = "HistoryEntry2"
                type = WhitelistDatabase.NameType.USERNAME
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
        }
        
        // When - Delete the user
        transaction {
            discordUser.delete()
        }
        
        // Then - History entries for this user should be deleted due to cascade
        transaction {
            val remainingEntries = WhitelistDatabase.DiscordUserName.find {
                WhitelistDatabase.DiscordUserNameHistory.discordUser eq discordUser.id
            }.toList()
            assertThat(remainingEntries).isEmpty()
        }
    }
    
    @Test
    fun `should cascade delete Minecraft username history when user is deleted`() {
        // Given
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "TestMCUser")
        
        // Add name history entries
        transaction {
            WhitelistDatabase.MinecraftUserName.new {
                this.minecraftUser = minecraftUser
                username = "MCHistoryEntry1"
                recordedBy = WhitelistDatabase.DiscordUser.getSystemUser()
            }
        }
        
        // When - Delete the user
        transaction {
            minecraftUser.delete()
        }
        
        // Then - History entries for this user should be deleted due to cascade
        transaction {
            val remainingEntries = WhitelistDatabase.MinecraftUserName.find {
                WhitelistDatabase.MinecraftUsernameHistory.minecraftUser eq minecraftUser.id
            }.toList()
            assertThat(remainingEntries).isEmpty()
        }
    }
}