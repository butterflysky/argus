package dev.butterflysky.argus.common

/** Reflection helpers shared by Fabric/NeoForge mixins to stay in sync. */
object LoginIntrospection {
    private val opMethods = arrayOf("isOperator", "isOp", "canBypassPlayerLimit")
    private val whitelistMethods = arrayOf("isWhitelisted", "isWhiteListed", "isAllowed", "isWhiteListed")
    private val removeMethods = arrayOf("removeFromWhitelist", "removeFromWhiteList", "removePlayerFromWhitelist", "removePlayerFromWhiteList")

    @JvmStatic
    fun isOp(target: Any, profile: Any): Boolean = callBool(target, opMethods, profile)

    @JvmStatic
    fun isWhitelisted(target: Any, profile: Any): Boolean = callBool(target, whitelistMethods, profile)

    @JvmStatic
    fun removeFromWhitelist(holder: Any, profile: Any) {
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

    private fun tryRemoveFromWhitelistAccessor(holder: Any, profile: Any) {
        val whitelist = runCatching { holder.javaClass.getMethod("getWhitelist").apply { isAccessible = true }.invoke(holder) }
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

    private fun callBool(target: Any, names: Array<String>, arg: Any): Boolean {
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
