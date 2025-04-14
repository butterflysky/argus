package dev.butterflysky.whitelist

import org.slf4j.LoggerFactory
import java.io.File

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