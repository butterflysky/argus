package dev.butterflysky.db

import dev.butterflysky.config.ArgusConfig
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Test implementation of ArgusConfig that returns fixed values without requiring the FabricLoader
 * This class provides test-specific configuration methods without depending on the real ArgusConfig
 */
class TestArgusConfig {
    
    companion object {
        private val testConfigData = ArgusConfig.ConfigData(
            whitelist = ArgusConfig.WhitelistConfig(
                cooldownHours = 48,
                defaultHistoryLimit = 10,
                defaultSearchLimit = 20,
                maxSearchLimit = 50
            )
        )
        
        /**
         * Get the test configuration data
         */
        fun get(): ArgusConfig.ConfigData = testConfigData
        
        /**
         * Calculate eligibility timestamp for tests
         * This replicates the logic in WhitelistDatabase.calculateEligibleTimestamp
         * but doesn't depend on ArgusConfig
         */
        fun calculateEligibleTimestamp(appliedAt: Instant): Instant {
            val cooldownHours = 48L // Fixed test value
            return appliedAt.plus(cooldownHours.hours)
        }
    }
}