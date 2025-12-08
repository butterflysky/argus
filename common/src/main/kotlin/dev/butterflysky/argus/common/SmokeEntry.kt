package dev.butterflysky.argus.common

/**
 * Minimal entrypoint used by jar-level smoke tests to ensure the shaded
 * production artifact can load our core classes and their transitive
 * dependencies without relying on the development classpath.
 */
object SmokeEntry {
    @JvmStatic
    fun main(args: Array<String>) {
        // Touch ArgusCore to trigger static initialization and any early
        // dependency resolution that would fail in production (e.g. Kotlin
        // stdlib version mismatches).
        Class.forName(ArgusCore::class.qualifiedName!!)
        println("ARGUS_SMOKE_OK")
    }
}
