package dev.butterflysky.argus.common

import java.util.Date
import java.util.UUID

/** Reflection helpers shared by Fabric/NeoForge mixins to stay in sync. */
object LoginIntrospection {
    private val opMethods = arrayOf("isOperator", "isOp", "canBypassPlayerLimit")
    private val whitelistMethods = arrayOf("isWhitelisted", "isWhiteListed", "isAllowed", "isWhiteListed")
    private val removeMethods =
        arrayOf("removeFromWhitelist", "removeFromWhiteList", "removePlayerFromWhitelist", "removePlayerFromWhiteList")

    @JvmStatic
    fun isOp(
        target: Any,
        profile: Any,
    ): Boolean = callBool(target, opMethods, profile)

    @JvmStatic
    fun isWhitelisted(
        target: Any,
        profile: Any,
    ): Boolean = callBool(target, whitelistMethods, profile)

    @JvmStatic
    fun removeFromWhitelist(
        holder: Any,
        profile: Any,
    ) {
        // Try direct methods on the holder first.
        for (name in removeMethods) {
            try {
                val m = holder.javaClass.getMethod(name, profile.javaClass)
                m.isAccessible = true
                m.invoke(holder, profile)
                return
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }

        // Try delegating to whitelist collection if present.
        tryRemoveFromWhitelistAccessor(holder, profile)
    }

    private fun tryRemoveFromWhitelistAccessor(
        holder: Any,
        profile: Any,
    ) {
        val whitelist =
            runCatching { holder.javaClass.getMethod("getWhitelist").apply { isAccessible = true }.invoke(holder) }
                .getOrElse {
                    runCatching { holder.javaClass.getMethod("getWhiteList").apply { isAccessible = true }.invoke(holder) }.getOrNull()
                }
                ?: return

        // Attempt remove(Object) as a generic fallback.
        runCatching {
            val remove = whitelist.javaClass.getMethod("remove", Any::class.java)
            remove.isAccessible = true
            remove.invoke(whitelist, profile)
            return
        }

        // Fabric sometimes exposes net.minecraft.server.WhitelistEntry
        runCatching {
            val entryCls = Class.forName("net.minecraft.server.WhitelistEntry")
            val ctor = entryCls.getConstructor(profile.javaClass)
            val entry = ctor.newInstance(profile)
            val remove = whitelist.javaClass.getMethod("remove", entryCls)
            remove.isAccessible = true
            remove.invoke(whitelist, entry)
        }
    }

    @JvmStatic
    fun ban(
        playerManager: Any,
        uuid: java.util.UUID,
        name: String?,
        reason: String,
        untilEpochMillis: Long?,
    ) {
        val banList = findBanList(playerManager) ?: return
        val profile = newProfile(uuid, name) ?: return
        val entry = newBanEntry(profile, reason, untilEpochMillis) ?: return
        runCatching { banList.javaClass.getMethod("add", Any::class.java).apply { isAccessible = true }.invoke(banList, entry) }
    }

    @JvmStatic
    fun unban(
        playerManager: Any,
        uuid: java.util.UUID,
    ) {
        val banList = findBanList(playerManager) ?: return
        val profile = newProfile(uuid, null) ?: return
        runCatching { banList.javaClass.getMethod("remove", Any::class.java).apply { isAccessible = true }.invoke(banList, profile) }
    }

    private fun findBanList(playerManager: Any): Any? {
        runCatching { playerManager.javaClass.getMethod("getUserBanList").apply { isAccessible = true }.invoke(playerManager) }
            .onSuccess { return it }
        return runCatching {
            playerManager.javaClass.getDeclaredField(
                "userBanList",
            ).apply { isAccessible = true }.get(playerManager)
        }.getOrNull()
    }

    private fun newBanEntry(
        profile: Any,
        reason: String,
        untilEpochMillis: Long?,
    ): Any? {
        val now = Date()
        val expires = untilEpochMillis?.let { Date(it) }
        val classNames =
            listOf(
                "net.minecraft.server.players.UserBanListEntry",
                "net.minecraft.world.level.storage.UserBanListEntry",
            )
        for (name in classNames) {
            val entry =
                runCatching {
                    val cls = Class.forName(name)
                    val ctor =
                        cls.getConstructor(
                            profile.javaClass,
                            Date::class.java,
                            String::class.java,
                            Date::class.java,
                            String::class.java,
                        )
                    ctor.newInstance(profile, now, "Argus", expires, reason)
                }.getOrNull()
            if (entry != null) return entry
        }
        return null
    }

    private fun newProfile(
        uuid: UUID,
        name: String?,
    ): Any? =
        runCatching {
            val cls = Class.forName("com.mojang.authlib.GameProfile")
            val ctor = cls.getConstructor(UUID::class.java, String::class.java)
            ctor.newInstance(uuid, name ?: "player")
        }.getOrNull()

    private fun callBool(
        target: Any,
        names: Array<String>,
        arg: Any,
    ): Boolean {
        for (name in names) {
            try {
                val m = target.javaClass.getMethod(name, arg.javaClass)
                m.isAccessible = true
                val res = m.invoke(target, arg)
                if (res is Boolean) return res
            } catch (_: Exception) {
            }
        }
        return false
    }
}
