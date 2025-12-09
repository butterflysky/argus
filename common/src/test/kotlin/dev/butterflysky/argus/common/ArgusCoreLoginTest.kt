package dev.butterflysky.argus.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class ArgusCoreLoginTest : ArgusTestBase() {
    @Test
    fun `ops bypass whitelist checks`() {
        configureArgus()
        val result = ArgusCore.onPlayerLogin(UUID.randomUUID(), "OpUser", isOp = true, isLegacyWhitelisted = false, whitelistEnabled = true)
        assertEquals(LoginResult.Allow, result)
    }

    @Test
    fun `discord down path honors active bans`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(false)
        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(banReason = "grief", banUntilEpochMillis = System.currentTimeMillis() + 60_000))

        val result = ArgusCore.onPlayerLogin(uuid, "BannedUser", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertTrue(result is LoginResult.Deny)
        assertTrue((result as LoginResult.Deny).message.contains("grief"))
    }

    @Test
    fun `cache sync updates mc name and records first allow`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 10L, discordName = "DiscordUser", hasAccess = true, mcName = "OldName"))

        val result = ArgusCore.onPlayerLogin(uuid, "NewName", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertEquals(LoginResult.Allow, result)
        assertEquals("NewName", CacheStore.get(uuid)?.mcName)
        val firstAllows = CacheStore.eventsSnapshot().filter { it.type == "first_allow" && it.targetUuid == uuid.toString() }
        assertEquals(1, firstAllows.size)
    }

    @Test
    fun `missing role revokes cached access but allows login`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 20L, discordName = "Member", hasAccess = false, mcName = "Member"))

        val result = ArgusCore.onPlayerLogin(uuid, "Member", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertEquals(LoginResult.Allow, result)
        assertEquals(false, CacheStore.get(uuid)?.hasAccess)
    }

    @Test
    fun `live role check grants access and records first allow`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride { RoleStatus.HasRole }

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 21L, discordName = "Member", hasAccess = false, mcName = "Member"))

        val result = ArgusCore.onPlayerLogin(uuid, "Member", isOp = false, isLegacyWhitelisted = false, whitelistEnabled = true)

        assertEquals(LoginResult.Allow, result)
        assertEquals(true, CacheStore.get(uuid)?.hasAccess)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "first_allow" && it.targetUuid == uuid.toString() })
    }

    @Test
    fun `legacy whitelisted but unlinked is kicked when enforced`() {
        configureArgus(enforcement = true)
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "LegacyUser"))

        val result = ArgusCore.onPlayerLogin(uuid, "LegacyUser", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        assertTrue(result is LoginResult.Deny)
        assertTrue((result as LoginResult.Deny).revokeWhitelist)
        assertTrue(result.message.contains("/link"))
        val legacyEvents = CacheStore.eventsSnapshot().filter { it.type == "first_legacy_kick" && it.targetUuid == uuid.toString() }
        assertEquals(1, legacyEvents.size)
        assertTrue(LinkTokenService.listActive().any { it.uuid == uuid })
    }

    @Test
    fun `legacy whitelisted dry-run does not kick`() {
        configureArgus(enforcement = false)
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "LegacyUser"))

        val result = ArgusCore.onPlayerLogin(uuid, "LegacyUser", isOp = false, isLegacyWhitelisted = true, whitelistEnabled = true)

        assertEquals(LoginResult.Allow, result)
        assertTrue(CacheStore.eventsSnapshot().any { it.type == "first_legacy_kick" && it.targetUuid == uuid.toString() })
    }

    @Test
    fun `join refresh kicks when role missing`() {
        configureArgus(enforcement = true)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride { RoleStatus.NotInGuild }

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 30L, discordName = "GoneUser", hasAccess = true, mcName = "GoneUser"))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = true)

        assertNotNull(message)
        assertTrue(message.contains("Access revoked"))
        assertEquals(false, CacheStore.get(uuid)?.hasAccess)
    }

    @Test
    fun `join allows when discord indeterminate`() {
        configureArgus(enforcement = true)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride { RoleStatus.Indeterminate }

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 40L, hasAccess = true, mcName = "Flaky"))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = true)
        assertNotNull(message)
        assertTrue(message.contains("Welcome"))
    }

    @Test
    fun `ops joining get link prompt when unlinked`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "Operator"))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = true, whitelistEnabled = true)

        assertNotNull(message)
        assertTrue(message.contains("/link"))
    }

    @Test
    fun `stranger join shows invite and link requirement when enforced`() {
        configureArgus(enforcement = true)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusConfig.update("discordInviteUrl", "https://discord.gg/example")

        val uuid = UUID.randomUUID()
        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = true)

        assertNotNull(message)
        assertTrue(message.contains("Link required"))
        assertTrue(message.contains("discord.gg/example"))
        assertTrue(LinkTokenService.listActive().any { it.uuid == uuid })
    }

    @Test
    fun `unlinked cached user gets gentle prompt when not enforced`() {
        configureArgus(enforcement = false)
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "Cached", discordId = null, hasAccess = false))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = true)
        assertNotNull(message)
        assertTrue(message.contains("Please link"))
    }

    @Test
    fun `join returns null for cached user without access`() {
        configureArgus()
        ArgusCore.setDiscordStartedOverride(true)

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(mcName = "Denied", hasAccess = false))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = false)
        assertNull(message)
    }

    @Test
    fun `refresh on join missing role in dry run updates cache but does not kick`() {
        configureArgus(enforcement = false)
        ArgusCore.setDiscordStartedOverride(true)
        ArgusCore.setRoleCheckOverride { RoleStatus.MissingRole }

        val uuid = UUID.randomUUID()
        CacheStore.upsert(uuid, PlayerData(discordId = 45L, discordName = "DryRun", hasAccess = true, mcName = "DryRun"))

        val message = ArgusCore.onPlayerJoin(uuid, isOp = false, whitelistEnabled = true)

        assertNull(message)
        assertEquals(false, CacheStore.get(uuid)?.hasAccess)
    }
}
