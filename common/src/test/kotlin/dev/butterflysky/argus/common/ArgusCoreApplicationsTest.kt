package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

class ArgusCoreApplicationsTest : ArgusTestBase() {
    @Test
    fun `approve application grants access and records event`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)
        val uuid = UUID.randomUUID()
        val app =
            WhitelistApplication(
                id = "app-1",
                discordId = 111L,
                mcName = "Applicant",
                resolvedUuid = uuid.toString(),
            )
        CacheStore.addApplication(app)

        val result = ArgusCore.approveApplication("app-1", actorDiscordId = 222L, reason = "ok")

        assertTrue(result.isSuccess)
        val pdata = CacheStore.get(uuid)
        assertEquals(true, pdata?.hasAccess)
        assertEquals(111L, pdata?.discordId)
        val updated = CacheStore.getApplication("app-1")
        assertEquals("approved", updated?.status)
        assertEquals("ok", updated?.reason)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "apply_approve" && it.targetUuid == uuid.toString() })
    }

    @Test
    fun `deny application updates status and logs`() {
        configureArgus()
        val app =
            WhitelistApplication(
                id = "app-2",
                discordId = 333L,
                mcName = "DeniedUser",
                resolvedUuid = UUID.randomUUID().toString(),
            )
        CacheStore.addApplication(app)

        val result = ArgusCore.denyApplication("app-2", actorDiscordId = 444L, reason = "nope")

        assertTrue(result.isSuccess)
        val updated = CacheStore.getApplication("app-2")
        assertEquals("denied", updated?.status)
        assertEquals("nope", updated?.reason)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "apply_deny" && it.targetDiscordId == 333L })
    }

    @Test
    fun `listPendingApplications orders pending only`() {
        val older = WhitelistApplication(id = "old", discordId = 1L, mcName = "Old", resolvedUuid = UUID.randomUUID().toString(), submittedAtEpochMillis = 1)
        val newer = WhitelistApplication(id = "new", discordId = 2L, mcName = "New", resolvedUuid = UUID.randomUUID().toString(), submittedAtEpochMillis = 2)
        CacheStore.addApplication(older)
        CacheStore.addApplication(newer.copy(status = "approved"))
        CacheStore.addApplication(newer)

        val pending = ArgusCore.listPendingApplications()
        assertEquals(listOf("old", "new"), pending.map { it.id })
    }
}
