package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

class ArgusCoreModerationTest : ArgusTestBase() {
    @Test
    fun `linkDiscordUser consumes token and updates cache`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        val token = LinkTokenService.issueToken(uuid, "LinkMe")
        var messengerCalled = false
        ArgusCore.registerMessenger { target, message ->
            messengerCalled = messengerCalled || (target == uuid && message.contains("Linked Discord user"))
        }

        val result = ArgusCore.linkDiscordUser(token, 500L, "DiscordUser", "Nick")

        assertTrue(result.isSuccess)
        val pdata = CacheStore.get(uuid)
        assertEquals(500L, pdata?.discordId)
        assertEquals("DiscordUser", pdata?.discordName)
        assertEquals(true, pdata?.hasAccess)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "link" && it.targetUuid == uuid.toString() })
        assertTrue(messengerCalled)
    }

    @Test
    fun `whitelist add and remove track events`() {
        configureArgus()
        val uuid = UUID.randomUUID()

        ArgusCore.whitelistAdd(uuid, mcName = "PlayerTwo", actor = "admin").getOrThrow()
        assertEquals(true, CacheStore.get(uuid)?.hasAccess)

        ArgusCore.whitelistRemove(uuid, actor = "admin").getOrThrow()
        assertEquals(false, CacheStore.get(uuid)?.hasAccess)
        assertTrue(ArgusCore.whitelistStatus(uuid).contains("hasAccess=false"))

        val events = CacheStore.eventsSnapshot().filter { it.targetUuid == uuid.toString() }.map { it.type }.toSet()
        assertTrue(events.contains("whitelist_add"))
        assertTrue(events.contains("whitelist_remove"))
    }

    @Test
    fun `warn ban and unban update state and mirrors`() {
        configureArgus()
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 900L, mcName = "Moderated"))
        var banMirrored = false
        var unbanMirrored = false
        ArgusCore.registerBanSync(
            ban = { target, _, _, _ -> if (target == uuid) banMirrored = true },
            unban = { target -> if (target == uuid) unbanMirrored = true },
        )

        ArgusCore.warnPlayer(uuid, actor = 900L, reason = "test warn").getOrThrow()
        ArgusCore.banPlayer(uuid, actor = 900L, reason = "test ban", untilEpochMillis = null).getOrThrow()
        ArgusCore.unbanPlayer(uuid, actor = 900L, reason = "test unban").getOrThrow()
        ArgusCore.commentOnPlayer(uuid, actor = 900L, note = "note").getOrThrow()

        val pdata = CacheStore.get(uuid)
        assertEquals(1, pdata?.warnCount)
        assertEquals(null, pdata?.banReason)
        assertTrue(banMirrored)
        assertTrue(unbanMirrored)

        val (warns, banReason) = ArgusCore.userWarningsAndBan(900L)
        assertEquals(1, warns)
        assertEquals("test ban", banReason)

        val review = ArgusCore.reviewPlayer(uuid).map { it.type }.toSet()
        assertTrue(review.containsAll(listOf("warn", "ban", "unban", "comment")))
    }
}
