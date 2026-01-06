package dev.butterflysky.argus.neoforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

final class PermissionBridge {
    private static final ConcurrentHashMap<Class<?>, Method> LEGACY_METHODS = new ConcurrentHashMap<>();

    private PermissionBridge() {}

    static boolean hasPermissionLevel(Object source, int level) {
        Method legacy = LEGACY_METHODS.computeIfAbsent(source.getClass(), PermissionBridge::findLegacy);
        if (legacy != null) {
            try {
                return (boolean) legacy.invoke(source, level);
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }

        try {
            Method permissionsMethod = source.getClass().getMethod("permissions");
            Object permissionSet = permissionsMethod.invoke(source);

            Class<?> permissionClass = Class.forName("net.minecraft.server.permissions.Permission");
            Class<?> permissionLevelClass = Class.forName("net.minecraft.server.permissions.PermissionLevel");
            Method byId = permissionLevelClass.getMethod("byId", int.class);
            Object levelEnum = byId.invoke(null, level);

            Class<?> hasCommandLevelClass = Class.forName("net.minecraft.server.permissions.Permission$HasCommandLevel");
            Constructor<?> ctor = hasCommandLevelClass.getConstructor(permissionLevelClass);
            Object permission = ctor.newInstance(levelEnum);

            Method hasPermission = permissionSet.getClass().getMethod("hasPermission", permissionClass);
            return (boolean) hasPermission.invoke(permissionSet, permission);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Method findLegacy(Class<?> clazz) {
        for (String name : new String[] {"hasPermission", "hasPermissions", "hasPermissionLevel"}) {
            try {
                return clazz.getMethod(name, int.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
