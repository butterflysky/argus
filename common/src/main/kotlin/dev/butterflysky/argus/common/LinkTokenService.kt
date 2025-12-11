package dev.butterflysky.argus.common

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Very small, in-memory token service. Future work can persist tokens or send via Discord.
 */
object LinkTokenService {
    private val random = SecureRandom()
    private val tokens: MutableMap<String, TokenEntry> = ConcurrentHashMap()
    private val reverse: MutableMap<UUID, TokenEntry> = ConcurrentHashMap()
    private val ttlMillis = 30.minutes.inWholeMilliseconds

    fun issueToken(
        uuid: UUID,
        mcName: String,
    ): String {
        cleanupExpired()
        val normalizedName = mcName.ifBlank { null }
        reverse[uuid]?.let { existing ->
            if (normalizedName != null && existing.mcName != normalizedName) {
                val updated = existing.copy(mcName = normalizedName)
                tokens[existing.token] = updated
                reverse[uuid] = updated
                return updated.token
            }
            return existing.token
        }
        val token = generateToken()
        val entry = TokenEntry(token, uuid, normalizedName, System.currentTimeMillis())
        tokens[token] = entry
        reverse[uuid] = entry
        return token
    }

    fun consume(token: String): TokenEntry? {
        cleanupExpired()
        val entry = tokens.remove(token) ?: return null
        reverse.remove(entry.uuid)
        return entry
    }

    fun listActive(): List<TokenStatus> {
        cleanupExpired()
        val now = System.currentTimeMillis()
        return tokens.values.map {
            TokenStatus(
                token = it.token,
                uuid = it.uuid,
                mcName = it.mcName,
                issuedAt = it.issuedAt,
                expiresInMillis = (it.issuedAt + ttlMillis - now).coerceAtLeast(0),
            )
        }.sortedBy { it.expiresInMillis }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanupExpired() {
        val cutoff = System.currentTimeMillis() - ttlMillis
        val expired = tokens.values.filter { it.issuedAt < cutoff }
        expired.forEach {
            tokens.remove(it.token)
            reverse.remove(it.uuid)
        }
    }

    data class TokenEntry(val token: String, val uuid: UUID, val mcName: String?, val issuedAt: Long)

    data class TokenStatus(val token: String, val uuid: UUID, val mcName: String?, val issuedAt: Long, val expiresInMillis: Long)
}
