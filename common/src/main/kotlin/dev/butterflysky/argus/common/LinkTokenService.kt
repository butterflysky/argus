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
    private val reverse: MutableMap<UUID, String> = ConcurrentHashMap()

    fun issueToken(
        uuid: UUID,
        mcName: String,
    ): String {
        reverse[uuid]?.let { existing -> return existing }
        val token = generateToken()
        tokens[token] = uuid
        reverse[uuid] = token
        return token
    }

    fun consume(token: String): UUID? {
        val uuid = tokens.remove(token)
        if (uuid != null) reverse.remove(uuid)
        return uuid
    }

    private fun generateToken(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
