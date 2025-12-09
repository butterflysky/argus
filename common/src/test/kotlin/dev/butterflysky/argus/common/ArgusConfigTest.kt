package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgusConfigTest : ArgusTestBase() {
    @Test
    fun `isConfigured false until required fields set`() {
        assertFalse(ArgusConfig.isConfigured())
        configureArgus(enforcement = false)
        assertTrue(ArgusConfig.isConfigured())
        assertEquals("false", ArgusConfig.get("enforcementEnabled").getOrNull())
    }

    @Test
    fun `update and sample values roundtrip`() {
        configureArgus()
        ArgusConfig.update("applicationMessage", "Apply in Discord").getOrThrow()
        assertTrue(ArgusConfig.updateFromJava("guildId", "99999"))

        val fields = ArgusConfig.fieldNames()
        assertTrue(fields.contains("guildId"))
        assertEquals("https://discord.gg/yourserver", ArgusConfig.sampleValue("discordInviteUrl"))
        assertEquals("Apply in Discord", ArgusConfig.get("applicationMessage").getOrNull())
    }
}
