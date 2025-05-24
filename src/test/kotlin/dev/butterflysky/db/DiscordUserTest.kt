package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the DiscordUser entity
 */
class DiscordUserTest : DatabaseTestBase() {
    
    @Test
    fun `should create a Discord user successfully`() {
        // When
        val username = "TestUser"
        val user = createTestDiscordUser(username)
        
        // Then
        transaction {
            assertThat(user.id.value).isGreaterThan(0)
            assertThat(user.currentUsername).isEqualTo(username)
            assertThat(user.isInServer).isTrue()
            assertThat(user.joinedServerAt).isNotNull()
            assertThat(user.currentServername).isNull()
        }
    }
    
    @Test
    fun `should retrieve an existing Discord user`() {
        // Given
        val username = "TestUser"
        val user = createTestDiscordUser(username)
        val userId = user.id.value
        
        // When
        val retrievedUser = transaction {
            WhitelistDatabase.DiscordUser.findById(userId)
        }
        
        // Then
        assertThat(retrievedUser).isNotNull
        transaction {
            assertThat(retrievedUser!!.currentUsername).isEqualTo(username)
            assertThat(retrievedUser.isInServer).isTrue()
        }
    }
    
    @Test
    fun `should mark user as left`() {
        // Given
        val user = createTestDiscordUser("TestUser")
        
        // When
        transaction {
            user.markAsLeft()
        }
        
        // Then
        transaction {
            assertThat(user.isInServer).isFalse()
            assertThat(user.leftServerAt).isNotNull()
            // Check that leftServerAt is roughly now
            val now = Clock.System.now()
            val fiveSecondsAgo = now.minus(5.seconds)
            val fiveSecondsHence = now.plus(5.seconds)
            assertThat(user.leftServerAt!! >= fiveSecondsAgo).isTrue()
            assertThat(user.leftServerAt!! <= fiveSecondsHence).isTrue()
        }
    }
    
    @Test
    fun `should mark user as rejoined`() {
        // Given
        val user = createTestDiscordUser("TestUser")
        transaction {
            user.markAsLeft()
        }
        
        // When
        transaction {
            user.markAsRejoined()
        }
        
        // Then
        transaction {
            assertThat(user.isInServer).isTrue()
            assertThat(user.leftServerAt).isNull()
        }
    }
    
    @Test
    fun `should get system user`() {
        // When
        val systemUser = transaction {
            WhitelistDatabase.DiscordUser.getSystemUser()
        }
        
        // Then
        transaction {
            assertThat(systemUser.id.value).isEqualTo(WhitelistDatabase.SYSTEM_USER_ID)
            assertThat(systemUser.currentUsername).isEqualTo("System")
            assertThat(systemUser.isInServer).isTrue()
        }
    }
    
    @Test
    fun `should get unmapped user`() {
        // When
        val unmappedUser = transaction {
            WhitelistDatabase.DiscordUser.getUnmappedUser()
        }
        
        // Then
        transaction {
            assertThat(unmappedUser.id.value).isEqualTo(WhitelistDatabase.UNMAPPED_DISCORD_ID)
            assertThat(unmappedUser.currentUsername).isEqualTo("Unmapped Minecraft User")
            assertThat(unmappedUser.isInServer).isFalse()
        }
    }
}