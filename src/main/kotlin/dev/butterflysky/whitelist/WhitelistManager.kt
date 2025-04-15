package dev.butterflysky.whitelist

import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages access to whitelist information
 */
class WhitelistManager private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-whitelist")
        private val instance = WhitelistManager()
        
        fun getInstance(): WhitelistManager {
            return instance
        }
    }
    
    private var whitelistFile: File? = null
    private var isWhitelistEnabled: Boolean = false
    private var whitelistedPlayers: List<String> = emptyList()
    private var lastUpdate: Long = 0
    
    /**
     * Set the whitelist file location
     */
    fun setWhitelistFile(file: File) {
        whitelistFile = file
        logger.info("Whitelist file location set to: ${file.absolutePath}")
        reloadWhitelistFromDisk()
    }
    
    /**
     * Update whitelist information from the server
     */
    @Synchronized
    fun updateWhitelistInfo(enabled: Boolean, players: List<String>) {
        isWhitelistEnabled = enabled
        whitelistedPlayers = players.sorted()
        lastUpdate = System.currentTimeMillis()
        logger.info("Whitelist information updated: enabled=$enabled, players=${players.size}")
    }
    
    /**
     * Force reload whitelist information from disk
     */
    @Synchronized
    fun reloadWhitelistFromDisk() {
        val file = whitelistFile ?: return
        
        if (!file.exists()) {
            logger.warn("Whitelist file does not exist at ${file.absolutePath}")
            return
        }
        
        try {
            // Parse the JSON file to get player names
            val fileContent = file.readText()
            // Use a simple regex to extract names from the JSON
            val namePattern = "\"name\":\\s*\"([^\"]+)\"".toRegex()
            val matches = namePattern.findAll(fileContent)
            val names = matches.map { it.groupValues[1] }.toList().sorted()
            
            whitelistedPlayers = names
            lastUpdate = System.currentTimeMillis()
            logger.info("Whitelist reloaded from disk, found ${names.size} players")
        } catch (e: Exception) {
            logger.error("Error reading whitelist file", e)
        }
    }
    
    /**
     * Get whitelist status
     */
    fun isEnabled(): Boolean {
        return isWhitelistEnabled
    }
    
    /**
     * Get list of whitelisted players
     */
    fun getWhitelistedPlayers(): List<String> {
        return whitelistedPlayers
    }
    
    /**
     * Get whitelist information as a formatted message
     */
    fun getWhitelistInfo(): String {
        val status = if (isWhitelistEnabled) "enabled" else "disabled"
        val playerList = if (whitelistedPlayers.isEmpty()) {
            "No players are whitelisted"
        } else {
            "Whitelisted players: ${whitelistedPlayers.joinToString(", ")}"
        }
        
        return "Whitelist is $status. $playerList"
    }
}

/**
 * Manages token-based linking between Minecraft and Discord accounts
 */
class LinkManager private constructor() {
    companion object {
        private val logger = LoggerFactory.getLogger("argus-links")
        private val instance = LinkManager()
        
        // Token expiration time in minutes
        private const val TOKEN_EXPIRATION_MINUTES = 10L
        
        fun getInstance(): LinkManager {
            return instance
        }
    }
    
    // Data structure to hold pending link tokens
    data class LinkRequest(
        val token: String,
        val minecraftUuid: UUID,
        val minecraftUsername: String,
        val createdAt: Long,
        var isProcessed: Boolean = false
    )
    
    // Map of tokens to link requests
    private val pendingLinks = ConcurrentHashMap<String, LinkRequest>()
    
    // Map of Minecraft UUIDs to their latest token
    private val playerTokens = ConcurrentHashMap<UUID, String>()
    
    // Scheduler for token cleanup
    private val cleanupScheduler = Executors.newSingleThreadScheduledExecutor()
    
    init {
        // Schedule token cleanup task
        cleanupScheduler.scheduleAtFixedRate(
            { cleanupExpiredTokens() },
            5, 5, TimeUnit.MINUTES
        )
        
        logger.info("Link manager initialized")
    }
    
    /**
     * Create a new token for linking a Minecraft account to Discord
     */
    fun createLinkToken(minecraftUuid: UUID, minecraftUsername: String): String {
        // Check if player already has a valid token
        val existingToken = playerTokens[minecraftUuid]
        if (existingToken != null) {
            val existingRequest = pendingLinks[existingToken]
            if (existingRequest != null && !isTokenExpired(existingRequest) && !existingRequest.isProcessed) {
                return existingToken
            }
        }
        
        // Generate a new token - 6-digit numeric code for easy typing
        val token = generateToken()
        
        // Store the link request
        val linkRequest = LinkRequest(
            token = token,
            minecraftUuid = minecraftUuid,
            minecraftUsername = minecraftUsername,
            createdAt = System.currentTimeMillis()
        )
        
        pendingLinks[token] = linkRequest
        playerTokens[minecraftUuid] = token
        
        logger.info("Created link token $token for player $minecraftUsername ($minecraftUuid)")
        
        return token
    }
    
    /**
     * Find a link request by token
     */
    fun getLinkRequestByToken(token: String): LinkRequest? {
        val request = pendingLinks[token] ?: return null
        
        // Check if token is expired
        if (isTokenExpired(request)) {
            pendingLinks.remove(token)
            return null
        }
        
        return request
    }
    
    /**
     * Mark a token as processed and remove it
     */
    fun markTokenAsProcessed(token: String): Boolean {
        val request = pendingLinks[token] ?: return false
        
        // Check if token is expired
        if (isTokenExpired(request)) {
            pendingLinks.remove(token)
            return false
        }
        
        // Mark as processed
        request.isProcessed = true
        
        // Remove the token from the maps
        pendingLinks.remove(token)
        playerTokens.remove(request.minecraftUuid)
        
        return true
    }
    
    /**
     * Check if a token is expired
     */
    private fun isTokenExpired(request: LinkRequest): Boolean {
        val now = System.currentTimeMillis()
        val expirationTime = request.createdAt + (TOKEN_EXPIRATION_MINUTES * 60 * 1000)
        return now > expirationTime
    }
    
    /**
     * Generate a unique 6-digit token
     */
    private fun generateToken(): String {
        val tokenInt = (100000..999999).random()
        val token = tokenInt.toString()
        
        // Ensure token is unique
        return if (pendingLinks.containsKey(token)) {
            generateToken()
        } else {
            token
        }
    }
    
    /**
     * Clean up expired tokens
     */
    private fun cleanupExpiredTokens() {
        try {
            val now = System.currentTimeMillis()
            val expirationThreshold = now - (TOKEN_EXPIRATION_MINUTES * 60 * 1000)
            
            // Find and remove expired tokens
            val expiredTokens = pendingLinks.entries.filter { 
                it.value.createdAt < expirationThreshold 
            }.map { it.key }
            
            if (expiredTokens.isNotEmpty()) {
                expiredTokens.forEach { token ->
                    val request = pendingLinks[token]
                    if (request != null) {
                        playerTokens.remove(request.minecraftUuid)
                    }
                    pendingLinks.remove(token)
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
            cleanupScheduler.shutdown()
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow()
            }
        } catch (e: Exception) {
            logger.error("Error shutting down link manager", e)
        }
    }
}