package dev.butterflysky.whitelist

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import dev.butterflysky.config.ArgusConfig

/**
 * Manages token-based linking between Minecraft and Discord accounts
 */
class LinkManager private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-links")
        private val instance = LinkManager()
        
        fun getInstance() = instance
    }
    
    // Map of tokens to link requests
    private val linkTokens = ConcurrentHashMap<String, LinkRequest>()
    
    // Map of Minecraft UUIDs to their latest token
    private val playerTokens = ConcurrentHashMap<UUID, String>()
    
    // Scheduler for token cleanup
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    
    init {
        // Schedule token cleanup task
        scheduler.scheduleAtFixedRate(
            { cleanupExpiredTokens() },
            5, 5, TimeUnit.MINUTES
        )
        
        logger.info("Link manager initialized")
    }
    
    /**
     * Create a link token for a Minecraft player
     */
    fun createLinkToken(minecraftUuid: UUID, minecraftUsername: String): String {
        // Check if player already has a valid token
        playerTokens[minecraftUuid]?.let { existingToken ->
            linkTokens[existingToken]?.let { request ->
                if (!isTokenExpired(request)) {
                    return existingToken
                }
            }
        }
        
        // Generate a new token - alphanumeric for easier recognition
        val token = UUID.randomUUID().toString().take(8)
        
        // Store the request
        val request = LinkRequest(
            token = token,
            minecraftUuid = minecraftUuid,
            minecraftUsername = minecraftUsername,
            createdAt = System.currentTimeMillis()
        )
        
        linkTokens[token] = request
        playerTokens[minecraftUuid] = token
        
        logger.info("Created link token $token for player $minecraftUsername ($minecraftUuid)")
        return token
    }
    
    /**
     * Get a link request by token
     */
    fun getLinkRequestByToken(token: String): LinkRequest? {
        val request = linkTokens[token] ?: return null
        
        // Check if token has expired
        if (isTokenExpired(request)) {
            linkTokens.remove(token)
            logger.info("Rejected expired token: $token")
            return null
        }
        
        return request
    }
    
    /**
     * Mark a token as processed (processed tokens are removed)
     */
    fun markTokenAsProcessed(token: String) {
        linkTokens[token]?.let { request ->
            playerTokens.remove(request.minecraftUuid)
            linkTokens.remove(token)
            logger.info("Token $token marked as processed and removed")
        }
    }
    
    /**
     * Check if a token is expired
     */
    private fun isTokenExpired(request: LinkRequest): Boolean {
        val now = System.currentTimeMillis()
        val expiryMinutes = ArgusConfig.get().link.tokenExpiryMinutes
        val expirationTime = request.createdAt + TimeUnit.MINUTES.toMillis(expiryMinutes)
        return now > expirationTime
    }
    
    /**
     * Clean up expired tokens
     */
    private fun cleanupExpiredTokens() {
        try {
            val now = System.currentTimeMillis()
            val expiryMinutes = ArgusConfig.get().link.tokenExpiryMinutes
            val expirationThreshold = now - TimeUnit.MINUTES.toMillis(expiryMinutes)
            
            // Find and remove expired tokens
            val expiredTokens = linkTokens.entries
                .filter { it.value.createdAt < expirationThreshold }
                .map { it.key }
            
            if (expiredTokens.isNotEmpty()) {
                expiredTokens.forEach { token ->
                    linkTokens[token]?.let { request ->
                        playerTokens.remove(request.minecraftUuid)
                    }
                    linkTokens.remove(token)
                }
                
                logger.info("Cleaned up ${expiredTokens.size} expired link tokens")
            }
        } catch (e: Exception) {
            logger.error("Error cleaning up expired tokens", e)
        }
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