package dev.butterflysky.argus.common

import java.util.UUID

/**
 * Lightweight scripted harness for login/join flows (no Minecraft server), used by integration-style tests.
 */
class LoginHarness {
    data class Outcome(val login: LoginResult, val joinMessage: String?)

    fun run(
        uuid: UUID,
        name: String,
        vanillaWhitelisted: Boolean,
        isOp: Boolean = false,
        whitelistEnabled: Boolean = true,
    ): Outcome {
        val login = ArgusCore.onPlayerLogin(uuid, name, isOp, vanillaWhitelisted, whitelistEnabled)
        val joinMsg = ArgusCore.onPlayerJoin(uuid, isOp, whitelistEnabled)
        return Outcome(login, joinMsg)
    }
}
