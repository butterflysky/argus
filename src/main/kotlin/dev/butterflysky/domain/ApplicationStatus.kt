package dev.butterflysky.domain // Updated package

/**
 * Represents the status of a whitelist application.
 */
enum class ApplicationStatus {
    PENDING,  // Application is awaiting review
    APPROVED, // Application has been approved
    REJECTED  // Application has been rejected
}
