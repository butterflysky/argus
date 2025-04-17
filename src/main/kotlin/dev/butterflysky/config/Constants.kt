package dev.butterflysky.config

import java.awt.Color

/**
 * Application-wide constants to avoid repeating literal values
 */
object Constants {
    // Discord UI colors
    val SUCCESS_COLOR = Color.GREEN
    val ERROR_COLOR = Color.RED
    val INFO_COLOR = Color.BLUE
    val WARNING_COLOR = Color(0xFF9800) // Orange
    
    // Default role names
    const val DEFAULT_ADMIN_ROLE = "Admin"
    const val DEFAULT_MODERATOR_ROLE = "Moderator"
    
    // Database constants
    const val TRANSACTION_RETRY_COUNT = 3
}