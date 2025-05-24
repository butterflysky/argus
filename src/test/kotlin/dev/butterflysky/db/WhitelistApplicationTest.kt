package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import java.util.UUID

/**
 * Tests for the WhitelistApplication entity and related functionality
 */
class WhitelistApplicationTest : DatabaseTestBase() {
    
    @Test
    fun `should create a whitelist application successfully`() {
        // Given
        val discordUser = createTestDiscordUser("ApplicantUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "MinecraftApplicant", discordUser)
        
        // When
        val application = transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = WhitelistDatabase.ApplicationStatus.PENDING
                appliedAt = Clock.System.now()
                eligibleAt = TestArgusConfig.calculateEligibleTimestamp(Clock.System.now())
                isModeratorCreated = false
            }
        }
        
        // Then
        transaction {
            assertThat(application.id.value).isGreaterThan(0)
            assertThat(application.discordUser.id.value).isEqualTo(discordUser.id.value)
            assertThat(application.minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(application.status).isEqualTo(WhitelistDatabase.ApplicationStatus.PENDING)
            assertThat(application.appliedAt).isNotNull()
            assertThat(application.eligibleAt > application.appliedAt).isTrue()
            assertThat(application.isModeratorCreated).isFalse()
            assertThat(application.processedAt).isNull()
            assertThat(application.processedBy).isNull()
            assertThat(application.overrideReason).isNull()
            assertThat(application.notes).isNull()
        }
    }
    
    @Test
    fun `should retrieve an existing whitelist application`() {
        // Given
        val discordUser = createTestDiscordUser("ApplicantUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "MinecraftApplicant", discordUser)
        val applicationId = transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = WhitelistDatabase.ApplicationStatus.PENDING
                appliedAt = Clock.System.now()
                eligibleAt = TestArgusConfig.calculateEligibleTimestamp(Clock.System.now())
                isModeratorCreated = false
            }.id.value
        }
        
        // When
        val retrievedApplication = transaction {
            WhitelistDatabase.WhitelistApplication.findById(applicationId)
        }
        
        // Then
        assertThat(retrievedApplication).isNotNull
        transaction {
            assertThat(retrievedApplication!!.discordUser.id.value).isEqualTo(discordUser.id.value)
            assertThat(retrievedApplication.minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(retrievedApplication.status).isEqualTo(WhitelistDatabase.ApplicationStatus.PENDING)
        }
    }
    
    @Test
    fun `should calculate eligible timestamp correctly`() {
        // Given
        val now = Clock.System.now()
        val testConfig = TestArgusConfig.get()
        val cooldownHours = testConfig.whitelist.cooldownHours.toLong()
        
        // When - use TestArgusConfig calculation
        val eligibleAt = TestArgusConfig.calculateEligibleTimestamp(now)
        
        // Then
        val expectedEligibleTime = now + cooldownHours.hours
        
        // Allow a small tolerance for calculation differences
        val tolerance = 2.seconds
        assertThat(eligibleAt).isGreaterThanOrEqualTo(expectedEligibleTime - tolerance)
        assertThat(eligibleAt).isLessThanOrEqualTo(expectedEligibleTime + tolerance)
    }
    
    @Test
    fun `should create moderator whitelist successfully`() {
        // Given
        val discordUser = createTestDiscordUser("ApplicantUser")
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "MinecraftApplicant", discordUser)
        val overrideReason = "Test moderator override"
        val notes = "Additional notes for this whitelist"
        
        // When
        val application = transaction {
            WhitelistDatabase.WhitelistApplication.createModeratorWhitelist(
                discordUser = discordUser,
                minecraftUser = minecraftUser,
                moderator = moderator,
                overrideReason = overrideReason,
                notes = notes
            )
        }
        
        // Then
        transaction {
            assertThat(application.id.value).isGreaterThan(0)
            assertThat(application.discordUser.id.value).isEqualTo(discordUser.id.value)
            assertThat(application.minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(application.status).isEqualTo(WhitelistDatabase.ApplicationStatus.APPROVED)
            assertThat(application.isModeratorCreated).isTrue()
            assertThat(application.processedAt).isNotNull()
            assertThat(application.processedBy?.id?.value).isEqualTo(moderator.id.value)
            assertThat(application.overrideReason).isEqualTo(overrideReason)
            assertThat(application.notes).isEqualTo(notes)
            
            // Verify the application is immediately eligible
            assertThat(application.eligibleAt <= application.appliedAt).isTrue()
        }
    }
    
    @Test
    fun `should create legacy whitelist successfully`() {
        // Given
        val moderator = createTestDiscordUser("ModeratorUser")
        val minecraftUser = createTestMinecraftUser(
            UUID.randomUUID(), 
            "LegacyMinecraftUser",
            null // No owner initially
        )
        val notes = "Legacy import notes"
        
        // When
        val application = transaction {
            WhitelistDatabase.WhitelistApplication.createLegacyWhitelist(
                minecraftUser = minecraftUser,
                moderator = moderator,
                notes = notes
            )
        }
        
        // Then
        transaction {
            assertThat(application.id.value).isGreaterThan(0)
            assertThat(application.discordUser.id.value).isEqualTo(WhitelistDatabase.UNMAPPED_DISCORD_ID)
            assertThat(application.minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(application.status).isEqualTo(WhitelistDatabase.ApplicationStatus.APPROVED)
            assertThat(application.isModeratorCreated).isTrue()
            assertThat(application.appliedAt).isNotEqualTo(Instant.fromEpochSeconds(0))
            assertThat(application.eligibleAt).isNotEqualTo(Instant.fromEpochSeconds(0))
            
            // Both timestamps should be very close to now
            val now = Clock.System.now()
            val tolerance = 5.seconds
            assertThat(application.appliedAt).isGreaterThanOrEqualTo(now - tolerance)
            assertThat(application.appliedAt).isLessThanOrEqualTo(now + tolerance)
            assertThat(application.eligibleAt).isGreaterThanOrEqualTo(now - tolerance)
            assertThat(application.eligibleAt).isLessThanOrEqualTo(now + tolerance)
            assertThat(application.processedAt).isNotNull()
            assertThat(application.processedBy?.id?.value).isEqualTo(moderator.id.value)
            assertThat(application.overrideReason).isEqualTo("Legacy whitelist import")
            assertThat(application.notes).isEqualTo(notes)
            
            // The test successfully created a legacy whitelist application
            // The currentOwner reference is updated in the importLegacyMinecraftUser method
            // but not in the createLegacyWhitelist method, so we don't test it here
            
            // Verify the application has the unmapped Discord user
            val unmappedUserId = WhitelistDatabase.UNMAPPED_DISCORD_ID
            assertThat(application.discordUser.id.value).isEqualTo(unmappedUserId)
        }
    }
    
    @Test
    fun `should find applications by Discord user`() {
        // Given
        val discordUser1 = createTestDiscordUser("User1")
        val discordUser2 = createTestDiscordUser("User2")
        val minecraftUser1 = createTestMinecraftUser(UUID.randomUUID(), "MCUser1", discordUser1)
        val minecraftUser2 = createTestMinecraftUser(UUID.randomUUID(), "MCUser2", discordUser2)
        
        // Create applications for both users
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                discordUser = discordUser1
                minecraftUser = minecraftUser1
                status = WhitelistDatabase.ApplicationStatus.PENDING
                appliedAt = Clock.System.now()
                eligibleAt = TestArgusConfig.calculateEligibleTimestamp(Clock.System.now())
                isModeratorCreated = false
            }
            
            WhitelistDatabase.WhitelistApplication.new {
                discordUser = discordUser2
                minecraftUser = minecraftUser2
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = discordUser2
            }
        }
        
        // When
        val applications1 = transaction {
            discordUser1.applications.toList()
        }
        
        val applications2 = transaction {
            discordUser2.applications.toList()
        }
        
        // Then
        assertThat(applications1).hasSize(1)
        assertThat(applications2).hasSize(1)
        
        transaction {
            assertThat(applications1[0].discordUser.id.value).isEqualTo(discordUser1.id.value)
            assertThat(applications1[0].status).isEqualTo(WhitelistDatabase.ApplicationStatus.PENDING)
            
            assertThat(applications2[0].discordUser.id.value).isEqualTo(discordUser2.id.value)
            assertThat(applications2[0].status).isEqualTo(WhitelistDatabase.ApplicationStatus.APPROVED)
        }
    }
    
    @Test
    fun `should find applications by Minecraft user`() {
        // Given
        val discordUser = createTestDiscordUser("ApplicantUser")
        val minecraftUser = createTestMinecraftUser(UUID.randomUUID(), "MinecraftUser", discordUser)
        
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = discordUser
            }
        }
        
        // When
        val applications = transaction {
            minecraftUser.applications.toList()
        }
        
        // Then
        assertThat(applications).hasSize(1)
        transaction {
            assertThat(applications[0].minecraftUser.id.value).isEqualTo(minecraftUser.id.value)
            assertThat(applications[0].status).isEqualTo(WhitelistDatabase.ApplicationStatus.APPROVED)
        }
    }
    
    @Test
    fun `should find applications by status`() {
        // Given
        val discordUser = createTestDiscordUser("TestUser")
        val minecraftUser1 = createTestMinecraftUser(UUID.randomUUID(), "MCUser1", discordUser)
        val minecraftUser2 = createTestMinecraftUser(UUID.randomUUID(), "MCUser2", discordUser)
        val minecraftUser3 = createTestMinecraftUser(UUID.randomUUID(), "MCUser3", discordUser)
        
        // Create applications with different statuses
        transaction {
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser1
                status = WhitelistDatabase.ApplicationStatus.PENDING
                appliedAt = Clock.System.now()
                eligibleAt = TestArgusConfig.calculateEligibleTimestamp(Clock.System.now())
                isModeratorCreated = false
            }
            
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser2
                status = WhitelistDatabase.ApplicationStatus.APPROVED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = discordUser
            }
            
            WhitelistDatabase.WhitelistApplication.new {
                this.discordUser = discordUser
                this.minecraftUser = minecraftUser3
                status = WhitelistDatabase.ApplicationStatus.REJECTED
                appliedAt = Clock.System.now()
                eligibleAt = Clock.System.now()
                isModeratorCreated = false
                processedAt = Clock.System.now()
                processedBy = discordUser
            }
        }
        
        // When
        val pendingApplications = transaction {
            WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.status eq WhitelistDatabase.ApplicationStatus.PENDING) and
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id)
            }.toList()
        }
        
        val approvedApplications = transaction {
            WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.status eq WhitelistDatabase.ApplicationStatus.APPROVED) and
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id)
            }.toList()
        }
        
        val rejectedApplications = transaction {
            WhitelistDatabase.WhitelistApplication.find {
                (WhitelistDatabase.WhitelistApplications.status eq WhitelistDatabase.ApplicationStatus.REJECTED) and
                (WhitelistDatabase.WhitelistApplications.discordUser eq discordUser.id)
            }.toList()
        }
        
        // Then
        assertThat(pendingApplications).hasSize(1)
        assertThat(approvedApplications).hasSize(1)
        assertThat(rejectedApplications).hasSize(1)
        
        transaction {
            assertThat(pendingApplications[0].minecraftUser.id.value).isEqualTo(minecraftUser1.id.value)
            assertThat(approvedApplications[0].minecraftUser.id.value).isEqualTo(minecraftUser2.id.value)
            assertThat(rejectedApplications[0].minecraftUser.id.value).isEqualTo(minecraftUser3.id.value)
        }
    }
}