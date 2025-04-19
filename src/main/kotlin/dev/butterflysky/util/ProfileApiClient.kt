package dev.butterflysky.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import net.minecraft.SharedConstants
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Custom API client for Minecraft profile lookups that properly handles rate limits.
 *
 * This client provides methods for looking up Minecraft profiles by username, with proper handling
 * of Mojang API rate limits. It respects Retry-After headers and implements exponential backoff
 * when rate limited.
 *
 * By design, this client:
 * 1. Uses a single-threaded executor to avoid parallel requests to Mojang APIs
 * 2. Maintains request rate to be a good API citizen
 * 3. Properly handles HTTP 429 rate limit responses
 * 4. Respects Retry-After headers from the server
 */
class ProfileApiClient private constructor() {
    private val logger = LoggerFactory.getLogger("argus-profile-api")
    private val gson = Gson()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    
    // Flag to signal when shutting down
    private val isShuttingDown = AtomicBoolean(false)
    
    // API constants
    companion object {
        // Mojang API URLs from YggdrasilEnvironment.PROD
        private const val SERVICES_HOST = "https://api.minecraftservices.com"
        
        // API endpoints - exactly match what Minecraft uses in YggdrasilGameProfileRepository
        private val PROFILE_BY_NAME_BASE_URL = SERVICES_HOST + "/minecraft/profile/lookup/name/"
        private val BULK_PROFILE_ENDPOINT = SERVICES_HOST + "/minecraft/profile/lookup/bulk/byname"
        
        // HTTP headers
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_ACCEPT_JSON = "application/json"
        const val HEADER_USER_AGENT = "User-Agent"
        
        // Get User-Agent header exactly as Minecraft does in AbstractTextFilterer.java
        val HEADER_USER_AGENT_VALUE by lazy {
            try {
                "Minecraft server" + SharedConstants.getGameVersion().getName()
            } catch (e: Exception) {
                // Fallback if we can't get the game version
                "Minecraft server 1.21.5"
            }
        }
        const val HEADER_RETRY_AFTER = "Retry-After"
        
        // Rate limiting and retry constants
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 30000L
        const val DELAY_BETWEEN_REQUESTS_MS = 100L
        
        // Cache settings
        const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes
        
        // Status codes
        const val STATUS_OK = 200
        const val STATUS_NOT_FOUND = 404
        const val STATUS_RATE_LIMITED = 429
        const val STATUS_SERVER_ERROR_MIN = 500
        
        // Singleton instance
        private val INSTANCE = ProfileApiClient()
        
        fun getInstance(): ProfileApiClient = INSTANCE
    }
    
    /**
     * Success response cache with expiration to avoid redundant lookups
     */
    private val profileCache = ConcurrentHashMap<String, CachedProfile>()
    
    /**
     * Find a Minecraft profile by username.
     * 
     * @param username The Minecraft username to look up
     * @return CompletableFuture with GameProfile if found, or null if not found or an error occurred
     */
    fun findProfileByNameAsync(username: String): CompletableFuture<GameProfile?> {
        val normalizedUsername = username.lowercase(Locale.ROOT)
        
        // Check cache first (this happens in the calling thread)
        val cachedProfile = profileCache[normalizedUsername]
        if (cachedProfile != null && !cachedProfile.isExpired()) {
            logger.debug("Cache hit for profile: $normalizedUsername")
            return CompletableFuture.completedFuture(cachedProfile.profile)
        }
        
        // Submit the lookup task to our dedicated single-threaded executor
        return CompletableFuture.supplyAsync({
            if (isShuttingDown.get()) {
                return@supplyAsync null
            }
            
            try {
                // Add a small delay between requests to be a good citizen
                try {
                    Thread.sleep(DELAY_BETWEEN_REQUESTS_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@supplyAsync null
                }
                
                // Perform the lookup
                val endpoint = PROFILE_BY_NAME_BASE_URL + normalizedUsername
                
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header(HEADER_ACCEPT, HEADER_ACCEPT_JSON)
                    .header(HEADER_USER_AGENT, HEADER_USER_AGENT_VALUE)
                    .GET()
                    .build()
                
                // Simple retry with backoff logic
                var retries = 0
                var backoffMs = INITIAL_BACKOFF_MS
                
                while (!isShuttingDown.get() && !Thread.currentThread().isInterrupted && retries <= MAX_RETRIES) {
                    try {
                        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                        
                        when (response.statusCode()) {
                            STATUS_OK -> {
                                // Parse the response and create a GameProfile
                                val responseBody = response.body()
                                if (responseBody.isNotEmpty()) {
                                    try {
                                        val jsonElement = JsonParser.parseString(responseBody)
                                        if (jsonElement.isJsonObject) {
                                            val jsonObject = jsonElement.asJsonObject
                                            if (jsonObject.has("id") && jsonObject.has("name")) {
                                                val id = jsonObject.get("id").asString
                                                val name = jsonObject.get("name").asString
                                                
                                                // Format the UUID correctly (Mojang APIs return UUIDs without hyphens)
                                                val uuid = formatUuid(id)
                                                val profile = GameProfile(uuid, name)
                                                
                                                // Cache the result
                                                profileCache[normalizedUsername] = CachedProfile(profile)
                                                
                                                return@supplyAsync profile
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logger.warn("Error parsing profile JSON for $normalizedUsername: ${e.message}")
                                    }
                                }
                                // No valid profile found in response
                                return@supplyAsync null
                            }
                            
                            STATUS_NOT_FOUND -> {
                                // Profile doesn't exist
                                logger.debug("Profile not found: $normalizedUsername")
                                return@supplyAsync null
                            }
                            
                            STATUS_RATE_LIMITED -> {
                                // Handle rate limiting
                                if (retries >= MAX_RETRIES) {
                                    logger.error("Maximum retries exceeded for profile lookup after rate limit: $normalizedUsername")
                                    return@supplyAsync null
                                }
                                
                                // Get retry delay from header if available
                                val retryAfterHeader = response.headers().firstValue(HEADER_RETRY_AFTER).orElse(null)
                                val sleepTime = if (retryAfterHeader != null) {
                                    try {
                                        retryAfterHeader.toLong() * 1000 // Convert to milliseconds
                                    } catch (e: NumberFormatException) {
                                        backoffMs
                                    }
                                } else {
                                    backoffMs
                                }
                                
                                logger.warn("Rate limited during profile lookup for $normalizedUsername. Retrying after ${sleepTime}ms")
                                
                                try {
                                    Thread.sleep(sleepTime)
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    return@supplyAsync null
                                }
                                
                                // Increase backoff for next attempt (exponential backoff)
                                backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                                retries++
                            }
                            
                            else -> {
                                // Handle server errors with retry
                                if (response.statusCode() >= STATUS_SERVER_ERROR_MIN) {
                                    if (retries >= MAX_RETRIES) {
                                        logger.error("Maximum retries exceeded for profile lookup after server error: $normalizedUsername")
                                        return@supplyAsync null
                                    }
                                    
                                    logger.warn("Server error (${response.statusCode()}) during profile lookup for $normalizedUsername. Retrying after ${backoffMs}ms")
                                    
                                    try {
                                        Thread.sleep(backoffMs)
                                    } catch (e: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        return@supplyAsync null
                                    }
                                    
                                    // Increase backoff for next attempt
                                    backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                                    retries++
                                } else {
                                    // Other client error
                                    logger.warn("Unexpected response for profile lookup [${response.statusCode()}]: $normalizedUsername")
                                    return@supplyAsync null
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // Network or connection error
                        if (retries >= MAX_RETRIES) {
                            logger.error("Maximum retries exceeded for profile lookup after I/O error: $normalizedUsername", e)
                            return@supplyAsync null
                        }
                        
                        logger.warn("I/O error during profile lookup for $normalizedUsername: ${e.message}. Retrying after ${backoffMs}ms")
                        
                        try {
                            Thread.sleep(backoffMs)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@supplyAsync null
                        }
                        
                        // Increase backoff for next attempt
                        backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                        retries++
                    } catch (e: InterruptedException) {
                        // Handle interruption explicitly
                        Thread.currentThread().interrupt()
                        return@supplyAsync null
                    } catch (e: Exception) {
                        // Unexpected error
                        logger.error("Unexpected error during profile lookup for $normalizedUsername", e)
                        return@supplyAsync null
                    }
                }
                
                // If we got here, we've exhausted retries or been interrupted
                if (Thread.currentThread().isInterrupted) {
                    logger.debug("Profile lookup for $normalizedUsername was interrupted")
                } else {
                    logger.error("Profile lookup for $normalizedUsername failed after $retries retries")
                }
                
                return@supplyAsync null
            } catch (e: Exception) {
                logger.error("Error in profile lookup for $normalizedUsername", e)
                return@supplyAsync null
            }
        }, ThreadPools.profileApiExecutor)
    }
    
    /**
     * Find a Minecraft profile by username, blocking until the result is available.
     * 
     * @param username The Minecraft username to look up
     * @return The GameProfile if found, or null if not found or an error occurred
     */
    fun findProfileByName(username: String): GameProfile? {
        return try {
            findProfileByNameAsync(username).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            when (e) {
                is InterruptedException -> Thread.currentThread().interrupt()
                is TimeoutException -> logger.warn("Profile lookup timed out for $username")
                else -> logger.error("Error getting profile for $username", e)
            }
            null
        }
    }
    
    /**
     * Find multiple Minecraft profiles by username in a serial manner to respect rate limits.
     * This implementation mirrors YggdrasilGameProfileRepository's approach but with better
     * rate limit handling.
     * 
     * @param usernames List of Minecraft usernames to look up
     * @return Map of usernames to their GameProfiles, excluding any that weren't found
     */
    fun findProfilesByNames(usernames: List<String>): Map<String, GameProfile> {
        if (usernames.isEmpty() || isShuttingDown.get()) {
            return emptyMap()
        }
        
        val result = ConcurrentHashMap<String, GameProfile>()
        val normalizedUsernames = usernames.map { it.lowercase(Locale.ROOT) }
        
        // Check cache first and collect usernames that need to be fetched
        val toFetch = normalizedUsernames.filter { username ->
            val cachedProfile = profileCache[username]
            if (cachedProfile != null && !cachedProfile.isExpired()) {
                result[username] = cachedProfile.profile
                false
            } else {
                true
            }
        }
        
        if (toFetch.isEmpty()) {
            logger.debug("All ${usernames.size} profiles found in cache")
            return result
        }
        
        logger.info("Looking up ${toFetch.size} profiles")
        
        // Process lookups one by one in the profile API thread to respect rate limits
        val future = CompletableFuture.supplyAsync({
            // Process each username sequentially
            for (username in toFetch) {
                if (isShuttingDown.get() || Thread.currentThread().isInterrupted) {
                    logger.info("Profile lookup batch interrupted, processed ${result.size}/${toFetch.size} profiles")
                    break
                }
                
                try {
                    // Small delay between requests to respect rate limits
                    try {
                        Thread.sleep(DELAY_BETWEEN_REQUESTS_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    
                    // Do the lookup inline rather than creating another async task
                    val endpoint = PROFILE_BY_NAME_BASE_URL + username
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header(HEADER_ACCEPT, HEADER_ACCEPT_JSON)
                        .header(HEADER_USER_AGENT, HEADER_USER_AGENT_VALUE)
                        .GET()
                        .build()
                    
                    var retries = 0
                    var backoffMs = INITIAL_BACKOFF_MS
                    
                    // Simple retry loop
                    retryLoop@ while (retries <= MAX_RETRIES && !isShuttingDown.get() && !Thread.currentThread().isInterrupted) {
                        try {
                            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                            
                            when (response.statusCode()) {
                                STATUS_OK -> {
                                    // Parse the response and create a GameProfile
                                    val responseBody = response.body()
                                    if (responseBody.isNotEmpty()) {
                                        try {
                                            val jsonElement = JsonParser.parseString(responseBody)
                                            if (jsonElement.isJsonObject) {
                                                val jsonObject = jsonElement.asJsonObject
                                                if (jsonObject.has("id") && jsonObject.has("name")) {
                                                    val id = jsonObject.get("id").asString
                                                    val name = jsonObject.get("name").asString
                                                    
                                                    // Format the UUID correctly
                                                    val uuid = formatUuid(id)
                                                    val profile = GameProfile(uuid, name)
                                                    
                                                    // Cache and add to results
                                                    profileCache[username] = CachedProfile(profile)
                                                    result[username] = profile
                                                    
                                                    logger.debug("Found profile for $username: $name ($uuid)")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            logger.warn("Error parsing profile JSON for $username: ${e.message}")
                                        }
                                    }
                                    // Continue to next username
                                    break@retryLoop
                                }
                                
                                STATUS_NOT_FOUND -> {
                                    // Profile doesn't exist, continue to next username
                                    logger.debug("Profile not found: $username")
                                    break@retryLoop
                                }
                                
                                STATUS_RATE_LIMITED -> {
                                    // Handle rate limiting
                                    if (retries >= MAX_RETRIES) {
                                        logger.error("Maximum retries exceeded for profile lookup after rate limit: $username")
                                        break@retryLoop
                                    }
                                    
                                    // Get retry delay from header if available
                                    val retryAfterHeader = response.headers().firstValue(HEADER_RETRY_AFTER).orElse(null)
                                    val sleepTime = if (retryAfterHeader != null) {
                                        try {
                                            retryAfterHeader.toLong() * 1000
                                        } catch (e: NumberFormatException) {
                                            backoffMs
                                        }
                                    } else {
                                        backoffMs
                                    }
                                    
                                    logger.warn("Rate limited during profile lookup for $username. Retrying after ${sleepTime}ms")
                                    
                                    try {
                                        Thread.sleep(sleepTime)
                                    } catch (e: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        break@retryLoop
                                    }
                                    
                                    backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                                    retries++
                                }
                                
                                else -> {
                                    // Handle server errors with retry
                                    if (response.statusCode() >= STATUS_SERVER_ERROR_MIN) {
                                        if (retries >= MAX_RETRIES) {
                                            logger.error("Maximum retries exceeded for profile lookup after server error: $username")
                                            break@retryLoop
                                        }
                                        
                                        logger.warn("Server error (${response.statusCode()}) during profile lookup for $username. Retrying after ${backoffMs}ms")
                                        
                                        try {
                                            Thread.sleep(backoffMs)
                                        } catch (e: InterruptedException) {
                                            Thread.currentThread().interrupt()
                                            break@retryLoop
                                        }
                                        
                                        backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                                        retries++
                                    } else {
                                        // Other client error, skip this username
                                        logger.warn("Unexpected response for profile lookup [${response.statusCode()}]: $username")
                                        break@retryLoop
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            when (e) {
                                is InterruptedException -> {
                                    // Handle interruption explicitly
                                    Thread.currentThread().interrupt()
                                    logger.debug("Profile lookup interrupted for $username")
                                    break@retryLoop
                                }
                                is IOException -> {
                                    // Network or connection error
                                    if (retries >= MAX_RETRIES) {
                                        logger.error("Maximum retries exceeded for profile lookup after I/O error: $username", e)
                                        break@retryLoop
                                    }
                                    
                                    logger.warn("I/O error during profile lookup for $username. Retrying after ${backoffMs}ms")
                                    
                                    try {
                                        Thread.sleep(backoffMs)
                                    } catch (ie: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        break@retryLoop
                                    }
                                    
                                    backoffMs = min(MAX_BACKOFF_MS, backoffMs * 2)
                                    retries++
                                }
                                else -> {
                                    // Unexpected error, skip this username
                                    logger.error("Unexpected error during profile lookup for $username", e)
                                    break@retryLoop
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error in profile lookup for $username", e)
                    // Continue to next username
                }
            }
            
            logger.info("Completed profile lookup batch, found ${result.size}/${toFetch.size} profiles")
            result
        }, ThreadPools.profileApiExecutor)
        
        // Wait for all lookups to complete with a reasonable timeout
        return try {
            future.get(60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            when (e) {
                is InterruptedException -> Thread.currentThread().interrupt()
                is TimeoutException -> logger.warn("Bulk profile lookup timed out after 60 seconds")
                else -> logger.error("Error in bulk profile lookup", e)
            }
            result // Return whatever we got so far
        }
    }
    
    /**
     * Signal the client to shutdown cleanly
     */
    fun shutdown() {
        isShuttingDown.set(true)
    }
    
    /**
     * Convert a Mojang UUID string (without hyphens) to a proper UUID
     */
    private fun formatUuid(id: String): UUID {
        return try {
            // Insert hyphens into the UUID format if they're missing
            // Convert from "8c2b80938d2a4719886ba877ae7968d1" to "8c2b8093-8d2a-4719-886b-a877ae7968d1"
            if (id.length == 32 && !id.contains("-")) {
                UUID.fromString(
                    id.substring(0, 8) + "-" +
                    id.substring(8, 12) + "-" +
                    id.substring(12, 16) + "-" +
                    id.substring(16, 20) + "-" +
                    id.substring(20)
                )
            } else {
                // Regular UUID format, parse directly
                UUID.fromString(id)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid UUID format: $id", e)
        }
    }
    
    /**
     * A profile with a timestamp for cache expiration.
     */
    private class CachedProfile(val profile: GameProfile) {
        private val timestamp: Long = System.currentTimeMillis()
        
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS
        }
    }
}