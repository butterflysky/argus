package dev.butterflysky.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Base class for database-related tests.
 * Sets up an in-memory H2 database for testing and creates the required tables.
 */
abstract class DatabaseTestBase {
    
    companion object {
        private val logger = LoggerFactory.getLogger(DatabaseTestBase::class.java)
        
        // Thread-safe set to track Discord IDs that have been used
        private val usedDiscordIds = ConcurrentHashMap.newKeySet<Long>()
        
        // Special IDs that should not be randomly generated
        private val reservedIds = setOf(
            WhitelistDatabase.UNMAPPED_DISCORD_ID,
            WhitelistDatabase.SYSTEM_USER_ID
        )
        
        /**
         * Generate a unique random ID that hasn't been used before
         */
        private fun generateUniqueId(): Long {
            var id: Long
            do {
                // Generate a random ID between 1000 and 1000000
                id = (1000L..1000000L).random()
            } while (id in usedDiscordIds || id in reservedIds)
            
            // Track this ID as used
            usedDiscordIds.add(id)
            return id
        }
    }
    
    @BeforeEach
    fun setUp() {
        // Connect to an in-memory H2 database for testing with a unique URL for each test
        // This ensures test isolation and prevents conflicts between tests
        val testId = System.currentTimeMillis()
        val dbUrl = "jdbc:h2:mem:test_$testId;DB_CLOSE_DELAY=-1"
        logger.info("Setting up test database: $dbUrl")
        
        // Initialize database with H2 
        val initialized = WhitelistDatabase.initializeWithJdbcUrl(dbUrl, "org.h2.Driver")
        if (!initialized) {
            throw IllegalStateException("Failed to initialize test database")
        }
    }
    
    /**
     * Creates a Discord user for testing
     * 
     * @param username Username for the test user
     * @return The created Discord user with a unique random ID
     */
    protected fun createTestDiscordUser(username: String): WhitelistDatabase.DiscordUser {
        val userId = generateUniqueId()
        return transaction {
            WhitelistDatabase.DiscordUser.new(userId) {
                currentUsername = username
                currentServername = null
                joinedServerAt = java.time.Instant.now()
                isInServer = true
            }
        }
    }
    
    /**
     * Creates a Minecraft user for testing
     */
    protected fun createTestMinecraftUser(
        uuid: UUID = UUID.randomUUID(), 
        username: String,
        owner: WhitelistDatabase.DiscordUser? = null
    ): WhitelistDatabase.MinecraftUser {
        return transaction {
            WhitelistDatabase.MinecraftUser.new(uuid) {
                currentUsername = username
                currentOwner = owner
            }
        }
    }
}