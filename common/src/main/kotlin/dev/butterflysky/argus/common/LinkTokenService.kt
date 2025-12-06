package dev.butterflysky.argus.common

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Very small, in-memory token service. Future work can persist tokens or send via Discord.
 */
object LinkTokenService {
    private val random = SecureRandom()
    private val tokens: MutableMap<String, UUID> = ConcurrentHashMap()

    fun issueToken(uuid: UUID, mcName: String): String {
        val token = generateToken()
        tokens[token] = uuid
        return token
    }

    fun consume(token: String): UUID? = tokens.remove(token)

    private fun generateToken(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
