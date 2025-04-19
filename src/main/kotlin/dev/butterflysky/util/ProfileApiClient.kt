package dev.butterflysky.util

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.annotations.SerializedName
import com.mojang.authlib.GameProfile
import dev.butterflysky.config.ArgusConfig
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.math.min
import kotlin.math.pow

/**
 * Custom API client for Minecraft profile lookups that properly handles rate limits.
 *
 * This client provides methods for looking up Minecraft profiles by username, with proper handling
 * of Mojang API rate limits. It respects Retry-After headers and implements exponential backoff
 * when rate limited.
 */
class ProfileApiClient private constructor() {
    private val logger = LoggerFactory.getLogger("argus-profile-api")
    private val gson = Gson()
    private val client: HttpClient
    
    // API constants
    companion object {
        // Base URL for Minecraft profile services
        const val BASE_URL = "https://api.minecraftservices.com"
        
        // API endpoints
        const val SINGLE_PROFILE_ENDPOINT = "$BASE_URL/minecraft/profile/lookup/name/"
        const val BULK_PROFILE_ENDPOINT = "$BASE_URL/minecraft/profile/lookup/bulk/byname"
        
        // HTTP headers
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_ACCEPT_JSON = "application/json"
        const val HEADER_USER_AGENT = "User-Agent"
        const val HEADER_USER_AGENT_VALUE = "Argus Minecraft Mod"
        const val HEADER_RETRY_AFTER = "Retry-After"
        
        // Rate limiting and retry constants
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 30000L
        const val BATCH_SIZE = 2
        const val DELAY_BETWEEN_BATCHES_MS = 100L
        
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
    
    init {
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }
    
    /**
     * Find a Minecraft profile by username.
     * 
     * @param username The Minecraft username to look up
     * @return The GameProfile if found, or null if not found or an error occurred
     */
    fun findProfileByName(username: String): GameProfile? {
        val normalizedUsername = username.lowercase(Locale.ROOT)
        
        // Check cache first
        val cachedProfile = profileCache[normalizedUsername]
        if (cachedProfile != null && !cachedProfile.isExpired()) {
            logger.debug("Cache hit for profile: $normalizedUsername")
            return cachedProfile.profile
        }
        
        try {
            // Single name lookup
            val endpoint = SINGLE_PROFILE_ENDPOINT + normalizedUsername
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header(HEADER_ACCEPT, HEADER_ACCEPT_JSON)
                .header(HEADER_USER_AGENT, HEADER_USER_AGENT_VALUE)
                .GET()
                .build()
            
            val response = executeWithRetry { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            
            if (response.statusCode() == STATUS_OK) {
                try {
                    val profileDto = gson.fromJson(response.body(), ProfileDTO::class.java)
                    val profile = GameProfile(profileDto.getUUID(), profileDto.name)
                    
                    // Cache the result
                    profileCache[normalizedUsername] = CachedProfile(profile)
                    
                    return profile
                } catch (e: Exception) {
                    logger.warn("Error parsing profile response for $normalizedUsername", e)
                }
            } else if (response.statusCode() == STATUS_NOT_FOUND) {
                logger.debug("Profile not found: $normalizedUsername")
                return null
            } else {
                logger.warn("Unexpected response for profile lookup [${response.statusCode()}]: $normalizedUsername")
            }
        } catch (e: Exception) {
            logger.error("Error looking up profile for $normalizedUsername", e)
        }
        
        return null
    }
    
    /**
     * Find multiple Minecraft profiles by username.
     * 
     * @param usernames List of Minecraft usernames to look up
     * @return Map of usernames to their GameProfiles, excluding any that weren't found
     */
    fun findProfilesByNames(usernames: List<String>): Map<String, GameProfile> {
        if (usernames.isEmpty()) {
            return emptyMap()
        }
        
        val result = mutableMapOf<String, GameProfile>()
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
        
        // Split into batches to avoid overloading the API
        for (batch in toFetch.chunked(BATCH_SIZE)) {
            try {
                val batchResult = fetchBatch(batch)
                result.putAll(batchResult)
                
                // Sleep between batches to respect rate limits
                if (batch.size == BATCH_SIZE) {
                    Thread.sleep(DELAY_BETWEEN_BATCHES_MS)
                }
            } catch (e: Exception) {
                logger.error("Error fetching batch of profiles: ${batch.joinToString()}", e)
            }
        }
        
        return result
    }
    
    /**
     * Fetch a batch of profiles in a single API call.
     * 
     * @param usernames The usernames to fetch (should be limited to batchSize)
     * @return Map of usernames to their GameProfiles
     */
    private fun fetchBatch(usernames: List<String>): Map<String, GameProfile> {
        val result = mutableMapOf<String, GameProfile>()
        if (usernames.isEmpty()) {
            return result
        }
        
        try {
            // Build JSON array of usernames
            val usernamesJson = JsonArray()
            usernames.forEach { usernamesJson.add(it) }
            
            val requestBody = gson.toJson(usernamesJson)
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(BULK_PROFILE_ENDPOINT))
                .header(HEADER_CONTENT_TYPE, HEADER_CONTENT_TYPE_JSON)
                .header(HEADER_ACCEPT, HEADER_ACCEPT_JSON)
                .header(HEADER_USER_AGENT, HEADER_USER_AGENT_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
            
            val response = executeWithRetry { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            
            if (response.statusCode() == STATUS_OK) {
                try {
                    val profilesArray = gson.fromJson(response.body(), Array<ProfileDTO>::class.java)
                    profilesArray.forEach { profile ->
                        val gameProfile = GameProfile(profile.getUUID(), profile.name)
                        result[profile.name.lowercase(Locale.ROOT)] = gameProfile
                        
                        // Cache the result
                        profileCache[profile.name.lowercase(Locale.ROOT)] = CachedProfile(gameProfile)
                    }
                } catch (e: Exception) {
                    logger.error("Error parsing batch profile response", e)
                }
                
                logger.debug("Batch lookup found ${result.size}/${usernames.size} profiles")
            } else {
                logger.warn("Unexpected response for batch profile lookup [${response.statusCode()}]")
            }
        } catch (e: Exception) {
            logger.error("Error in batch profile lookup", e)
        }
        
        return result
    }
    
    /**
     * Execute an HTTP request with retry logic for rate limit handling.
     * 
     * @param request Function that performs the HTTP request
     * @return HttpResponse from the successful request
     * @throws IOException if all retries fail
     */
    private fun <T> executeWithRetry(request: () -> HttpResponse<T>): HttpResponse<T> {
        var retries = 0
        var backoffMs = INITIAL_BACKOFF_MS
        
        while (true) {
            try {
                val response = request()
                
                // If we hit a rate limit, apply backoff strategy
                if (response.statusCode() == STATUS_RATE_LIMITED) {
                    if (retries >= MAX_RETRIES) {
                        logger.error("Maximum retries exceeded for request after rate limit")
                        throw IOException("Maximum retries exceeded for request after rate limit")
                    }
                    
                    // Try to get Retry-After header for server-advised delay
                    val retryAfter = response.headers().firstValue(HEADER_RETRY_AFTER).orElse(null)
                    val sleepTime = if (retryAfter != null) {
                        try {
                            // Retry-After can be in seconds or an HTTP date
                            // For simplicity, we'll assume it's seconds
                            retryAfter.toLong() * 1000
                        } catch (e: NumberFormatException) {
                            // If we can't parse it, use exponential backoff
                            backoffMs
                        }
                    } else {
                        backoffMs
                    }
                    
                    logger.warn("Rate limited by Mojang API. Retrying after ${sleepTime}ms (retry ${retries + 1}/${MAX_RETRIES})")
                    try {
                        Thread.sleep(sleepTime)
                    } catch (ie: InterruptedException) {
                        // Properly propagate the interrupt
                        Thread.currentThread().interrupt()
                        logger.info("Rate limit backoff interrupted, propagating interrupt")
                        throw ie  // Rethrow the original InterruptedException
                    }
                    
                    // Increase backoff for next attempt
                    backoffMs = min(MAX_BACKOFF_MS, (backoffMs * 2))
                    retries++
                    continue
                }
                
                // For server errors, also retry with backoff
                if (response.statusCode() >= STATUS_SERVER_ERROR_MIN) {
                    if (retries >= MAX_RETRIES) {
                        logger.error("Maximum retries exceeded for request after server error")
                        throw IOException("Maximum retries exceeded for request after server error")
                    }
                    
                    logger.warn("Server error from Mojang API (${response.statusCode()}). Retrying after ${backoffMs}ms (retry ${retries + 1}/${MAX_RETRIES})")
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        // Properly propagate the interrupt
                        Thread.currentThread().interrupt()
                        logger.info("Server error backoff interrupted, propagating interrupt")
                        throw ie  // Rethrow the original InterruptedException
                    }
                    
                    // Increase backoff for next attempt
                    backoffMs = min(MAX_BACKOFF_MS, (backoffMs * 2))
                    retries++
                    continue
                }
                
                return response
            } catch (e: InterruptedException) {
                // Properly propagate the interrupt
                Thread.currentThread().interrupt()
                logger.info("Profile lookup interrupted, propagating interrupt")
                throw e // Rethrow the original InterruptedException
            } catch (e: IOException) {
                if (retries >= MAX_RETRIES) {
                    logger.error("Maximum retries exceeded for request after I/O error", e)
                    throw e
                }
                
                logger.warn("I/O error during request. Retrying after ${backoffMs}ms (retry ${retries + 1}/${MAX_RETRIES})", e)
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    // Properly propagate the interrupt
                    Thread.currentThread().interrupt()
                    logger.info("Retry backoff interrupted, propagating interrupt")
                    throw ie // Rethrow the original InterruptedException
                }
                
                // Increase backoff for next attempt
                backoffMs = min(MAX_BACKOFF_MS, (backoffMs * 2))
                retries++
            }
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
    
    /**
     * DTO for profile data in API responses
     */
    private data class ProfileDTO(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String
    ) {
        /**
         * Convert the Mojang API UUID string (without hyphens) to a proper UUID
         */
        fun getUUID(): UUID {
            try {
                // Insert hyphens into the UUID format if they're missing
                // Convert from "8c2b80938d2a4719886ba877ae7968d1" to "8c2b8093-8d2a-4719-886b-a877ae7968d1"
                if (id.length == 32 && !id.contains("-")) {
                    return UUID.fromString(
                        id.substring(0, 8) + "-" +
                        id.substring(8, 12) + "-" +
                        id.substring(12, 16) + "-" +
                        id.substring(16, 20) + "-" +
                        id.substring(20)
                    )
                }
                
                // Regular UUID format, parse directly
                return UUID.fromString(id)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid UUID format: $id", e)
            }
        }
    }
}