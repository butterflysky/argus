package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginIntrospectionTest {
    data class FakeProfile(val name: String)

    class OpHolder {
        fun isOperator(profile: FakeProfile) = profile.name == "admin"
    }

    class WhitelistHolder {
        fun isWhiteListed(@Suppress("UNUSED_PARAMETER") profile: FakeProfile) = true
    }

    class AccessorHolder {
        val backing = mutableListOf<FakeProfile>()
        fun getWhitelist(): MutableList<FakeProfile> = backing
    }

    @Test
    fun `reflection helpers detect op and whitelist`() {
        val profile = FakeProfile("admin")
        assertTrue(LoginIntrospection.isOp(OpHolder(), profile))
        assertTrue(LoginIntrospection.isWhitelisted(WhitelistHolder(), profile))
    }

    @Test
    fun `removeFromWhitelist falls back to accessor`() {
        val holder = AccessorHolder()
        val profile = FakeProfile("player1")
        holder.backing.add(profile)

        LoginIntrospection.removeFromWhitelist(holder, profile)

        assertEquals(0, holder.backing.size)
    }
}
