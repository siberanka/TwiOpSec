package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.SecuritySettings;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Removes protected grants through native APIs without dispatching configurable commands. */
public final class PermissionRemediator {
    private final TwiOpSecPlugin plugin;
    private final AtomicReference<SecuritySettings> settings;
    private final AtomicReference<AuditLogger> audit;
    private volatile LuckPermsHook luckPerms;
    private volatile VaultBridge vault;

    public PermissionRemediator(TwiOpSecPlugin plugin, AtomicReference<SecuritySettings> settings,
                                AtomicReference<AuditLogger> audit) {
        this.plugin = plugin;
        this.settings = settings;
        this.audit = audit;
    }

    public synchronized void start() {
        SecuritySettings snapshot = settings.get();
        if (snapshot.luckPermsNativeHook() && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                if (luckPerms == null) {
                    luckPerms = LuckPermsHook.create(plugin, settings, audit);
                    plugin.getLogger().info("LuckPerms protected-permission mutation hook enabled.");
                }
                luckPerms.reconcileLoadedData();
            } catch (LinkageError | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "LuckPerms hook could not start; fallbacks remain active", exception);
            }
        }
        if (vault == null && snapshot.vaultFallback() && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            vault = VaultBridge.discover(plugin);
        }
    }

    public void removeFromPlayer(Player player, String detectedPermission, CheckTrigger trigger) {
        SecuritySettings snapshot = settings.get();
        if (!snapshot.permissionRemediationEnabled() || snapshot.isTrustedPermissionHolder(player.getUniqueId())) {
            return;
        }
        boolean changed = snapshot.luckPermsNativeHook() && luckPerms != null
                && luckPerms.removeDirectProtectedNodes(player.getUniqueId());
        if (snapshot.bukkitAttachmentFallback()) {
            changed |= removeAttachments(player, snapshot);
        }
        if (snapshot.vaultFallback() && vault != null) {
            changed |= vault.remove(player, detectedPermission);
        }
        record(changed ? "protected-permission-auto-unset" : "protected-permission-unset-unresolved",
                player.getName(), player.getUniqueId().toString(), detectedPermission, trigger.name());
    }

    static boolean removeAttachments(Player player, SecuritySettings settings) {
        boolean changed = false;
        for (PermissionAttachmentInfo info : List.copyOf(player.getEffectivePermissions())) {
            PermissionAttachment attachment = info.getAttachment();
            if (!info.getValue() || attachment == null || settings.protectedPattern(info.getPermission()) == null) {
                continue;
            }
            try {
                attachment.unsetPermission(info.getPermission());
                changed = true;
            } catch (RuntimeException ignored) {
                // A provider-owned attachment can reject mutation; its native/Vault path is tried separately.
            }
        }
        return changed;
    }

    private void record(String action, String name, String uuid, String permission, String source) {
        AuditLogger logger = audit.get();
        if (logger != null) {
            logger.record(action, safe(name), uuid,
                    "permission=" + safe(permission) + ",source=" + safe(source));
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "unknown";
        }
        String clean = value.replaceAll("[^A-Za-z0-9_.*:-]", "?");
        return clean.substring(0, Math.min(clean.length(), 128));
    }

    /** Reflection keeps Vault optional while still using its registered permission provider API. */
    private record VaultBridge(Object provider, Method playerRemove, Method transientRemove) {
        static VaultBridge discover(TwiOpSecPlugin plugin) {
            Plugin vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault");
            if (vaultPlugin == null) {
                return null;
            }
            try {
                Class<?> permissionType = Class.forName("net.milkbowl.vault.permission.Permission", false,
                        vaultPlugin.getClass().getClassLoader());
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(permissionType);
                if (registration == null) {
                    plugin.getLogger().warning("Vault is present but no permission provider is registered.");
                    return null;
                }
                Object provider = registration.getProvider();
                Method remove = permissionType.getMethod("playerRemove", String.class, OfflinePlayer.class, String.class);
                Method transientRemove = permissionType.getMethod("playerRemoveTransient", OfflinePlayer.class,
                        String.class);
                plugin.getLogger().info("Vault permission fallback enabled for " + provider.getClass().getSimpleName() + '.');
                return new VaultBridge(provider, remove, transientRemove);
            } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Vault permission fallback is incompatible and remains disabled",
                        exception);
                return null;
            }
        }

        boolean remove(Player player, String permission) {
            if (permission == null || permission.isBlank()) {
                return false;
            }
            boolean changed = invoke(transientRemove, player, permission);
            changed |= invoke(playerRemove, null, player, permission);
            changed |= invoke(playerRemove, player.getWorld().getName(), player, permission);
            return changed;
        }

        private boolean invoke(Method method, Object... arguments) {
            try {
                return Boolean.TRUE.equals(method.invoke(provider, arguments));
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                return false;
            }
        }
    }
}
