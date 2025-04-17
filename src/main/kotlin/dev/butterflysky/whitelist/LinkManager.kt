package dev.butterflysky.whitelist

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import dev.butterflysky.config.ArgusConfig

/**
 * Manages the token-based linking between Minecraft and Discord accounts
 */
class LinkManager private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-link-manager")
        private val instance = LinkManager()
        
        fun getInstance(): LinkManager {
            return instance
        }
    }
    
    private val linkTokens = ConcurrentHashMap<String, LinkRequest>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    
    /**
     * Create a link token for a Minecraft player
     */
    fun createLinkToken(minecraftUuid: UUID, minecraftUsername: String): String {
        // Generate a simple random token
        val token = UUID.randomUUID().toString().substring(0, 8)
        
        // Store the request
        val request = LinkRequest(
            token = token,
            minecraftUuid = minecraftUuid,
            minecraftUsername = minecraftUsername,
            createdAt = System.currentTimeMillis()
        )
        
        linkTokens[token] = request
        
        // Schedule token expiration
        val tokenExpiryMinutes = ArgusConfig.get().link.tokenExpiryMinutes
        scheduler.schedule({
            linkTokens.remove(token)
            logger.info("Link token $token for $minecraftUsername expired")
        }, tokenExpiryMinutes, TimeUnit.MINUTES)
        
        logger.info("Created link token for player $minecraftUsername ($minecraftUuid): $token")
        return token
    }
    
    /**
     * Get a link request by token
     */
    fun getLinkRequestByToken(token: String): LinkRequest? {
        val request = linkTokens[token]
        
        // Check if token exists and has not expired
        if (request != null) {
            val ageMillis = System.currentTimeMillis() - request.createdAt
            val tokenExpiryMinutes = ArgusConfig.get().link.tokenExpiryMinutes
            if (ageMillis > TimeUnit.MINUTES.toMillis(tokenExpiryMinutes)) {
                // Token has expired, remove it
                linkTokens.remove(token)
                logger.info("Rejected expired token: $token")
                return null
            }
        }
        
        return request
    }
    
    /**
     * Mark a token as processed (processed tokens are removed)
     */
    fun markTokenAsProcessed(token: String) {
        linkTokens.remove(token)
        logger.info("Token $token marked as processed and removed")
    }
    
    /**
     * Shutdown the link manager
     */
    fun shutdown() {
        try {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
            logger.info("Link manager shutdown complete")
        } catch (e: Exception) {
            logger.error("Error shutting down link manager", e)
            scheduler.shutdownNow()
        }
    }
    
    /**
     * Data class for link requests
     */
    data class LinkRequest(
        val token: String,
        val minecraftUuid: UUID,
        val minecraftUsername: String,
        val createdAt: Long
    )
}