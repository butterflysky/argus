package dev.butterflysky.argus.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ApplicationFlowTest : ArgusTestBase() {
    @Test
    fun `rejects application when player already whitelisted`() {
        configureArgus()
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(hasAccess = true, mcName = "Tester"))
        ArgusCore.setProfileLookupOverride { uuid to "Tester" }
        val result = ArgusCore.submitApplication(111L, "Tester")
        assertTrue(result.isFailure, "Should fail when already whitelisted")
    }

    @Test
    fun `prevents duplicate pending applications`() {
        configureArgus()
        val uuid = UUID.randomUUID()
        ArgusCore.setProfileLookupOverride { uuid to "PlayerOne" }
        val first = ArgusCore.submitApplication(222L, "PlayerOne")
        assertTrue(first.isSuccess)
        val second = ArgusCore.submitApplication(222L, "PlayerOne")
        assertTrue(second.isFailure, "Second application should be blocked")
        val pending = ArgusCore.listPendingApplications()
        assertEquals(1, pending.size)
    }

    @Test
    fun `assigns incremental short ids and approves by discord`() {
        configureArgus()
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        ArgusCore.setProfileLookupOverride { name ->
            when (name) {
                "Alpha" -> uuid1
                "Beta" -> uuid2
                else -> null
            }?.let { it to name }
        }

        ArgusCore.submitApplication(333L, "Alpha")
        ArgusCore.submitApplication(444L, "Beta")
        val apps = ArgusCore.listPendingApplications()
        assertEquals(listOf(1, 2), apps.map { it.shortId })

        val approve = ArgusCore.approveApplicationForDiscord(444L, 999L, "ok")
        assertTrue(approve.isSuccess)
        val pd = CacheStore.findByDiscordId(444L)?.second
        assertTrue(pd?.hasAccess == true, "Approved applicant should be whitelisted")
    }
}
