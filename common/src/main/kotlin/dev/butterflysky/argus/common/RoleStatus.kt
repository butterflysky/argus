package dev.butterflysky.argus.common

/**
 * Result of a live Discord whitelist check.
 */
sealed interface RoleStatus {
    /** Member is in guild and has the whitelist role. */
    data object HasRole : RoleStatus

    /** Member is in guild but missing the whitelist role. */
    data object MissingRole : RoleStatus

    /** Member is not present in the configured guild. */
    data object NotInGuild : RoleStatus

    /** Could not determine (timeout/API down/etc). */
    data object Indeterminate : RoleStatus
}
