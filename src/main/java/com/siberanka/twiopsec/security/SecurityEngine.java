package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.SecuritySettings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class SecurityEngine {
    private final TwiOpSecPlugin plugin;
    private final AtomicReference<SecuritySettings> settings;
    private final AtomicReference<AuditLogger> audit;
    private final PermissionRemediator permissionRemediator;
    private volatile ScheduledTask periodicTask;

    public SecurityEngine(TwiOpSecPlugin plugin, AtomicReference<SecuritySettings> settings,
                          AtomicReference<AuditLogger> audit, PermissionRemediator permissionRemediator) {
        this.plugin = plugin;
        this.settings = settings;
        this.audit = audit;
        this.permissionRemediator = permissionRemediator;
    }

    public void restartPeriodicTask() {
        ScheduledTask current = periodicTask;
        if (current != null) {
            current.cancel();
        }
        SecuritySettings snapshot = settings.get();
        if (!snapshot.enabled() || !snapshot.periodicEnabled()) {
            periodicTask = null;
            return;
        }
        long ticks = Math.multiplyExact((long) snapshot.periodicSeconds(), 20L);
        periodicTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> sweepOnlinePlayers(), ticks, ticks);
    }

    /** Removes stale unauthorized entries already persisted in the server operator registry. */
    public void reconcileStoredOperators() {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            SecuritySettings snapshot = settings.get();
            if (!snapshot.enabled() || !snapshot.deopUnauthorized()) {
                return;
            }
            int removed = 0;
            for (OfflinePlayer operator : Bukkit.getOperators()) {
                UUID uuid = operator.getUniqueId();
                if (!snapshot.isTrustedOperator(uuid)) {
                    try {
                        operator.setOp(false);
                        removed++;
                        AuditLogger logger = audit.get();
                        if (logger != null) {
                            logger.record("unauthorized-stored-operator", safeOfflinePlayer(operator),
                                    uuid.toString(), "trigger=startup-registry-reconciliation");
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not remove an unauthorized stored operator entry", exception);
                    }
                }
            }
            if (removed > 0) {
                plugin.getLogger().warning("Removed " + removed
                        + " unauthorized entries from the stored operator registry.");
            }
        });
    }

    public void stop() {
        ScheduledTask current = periodicTask;
        periodicTask = null;
        if (current != null) {
            current.cancel();
        }
    }

    public boolean checkPlayer(Player player, CheckTrigger trigger) {
        SecuritySettings snapshot = settings.get();
        if (!snapshot.enabled() || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (player.isOp() && !snapshot.isTrustedOperator(uuid)) {
            enforceOperatorViolation(player, trigger, snapshot);
            return true;
        }
        String permission = protectedPermission(player, snapshot);
        if (permission != null && !snapshot.isTrustedPermissionHolder(uuid)) {
            if (snapshot.permissionRemediationEnabled()) {
                permissionRemediator.removeFromPlayer(player, permission, trigger);
            }
            enforcePermissionViolation(player, trigger, permission, snapshot);
            return true;
        }
        return false;
    }

    public boolean mayOpTarget(String name) {
        SecuritySettings snapshot = settings.get();
        if (name == null || name.isBlank()) {
            return false;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return snapshot.isTrustedOperator(online.getUniqueId());
        }
        if (snapshot.requireOnlineOpTarget()) {
            return false;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && snapshot.isTrustedOperator(cached.getUniqueId());
    }

    /** Resolves a permission grant by actual UUID, never by an informational whitelist name. */
    public boolean mayGrantPermissionTarget(String target) {
        SecuritySettings snapshot = settings.get();
        if (snapshot.isTrustedPermissionTarget(target)) {
            return true;
        }
        Player online = target == null ? null : Bukkit.getPlayerExact(target);
        return online != null && snapshot.isTrustedPermissionHolder(online.getUniqueId());
    }

    private void sweepOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> checkPlayer(player, CheckTrigger.PERIODIC), null, 1L);
        }
    }

    private void enforceOperatorViolation(Player player, CheckTrigger trigger, SecuritySettings snapshot) {
        String detail = "trigger=" + trigger.name().toLowerCase(Locale.ROOT);
        if (snapshot.deopUnauthorized()) {
            player.setOp(false);
        }
        dispatchConfigured(snapshot.unauthorizedOperatorCommands(), player, "*");
        record("unauthorized-operator", player, detail);
        plugin.getLogger().warning("Blocked unauthorized operator " + safePlayer(player) + " (" + detail + ")");
        if (snapshot.kickUnauthorizedOperator() && player.isOnline()) {
            player.kick(Component.text(snapshot.kickMessage()));
        }
    }

    private void enforcePermissionViolation(Player player, CheckTrigger trigger, String permission,
                                            SecuritySettings snapshot) {
        String detail = "permission=" + safePermission(permission) + ",trigger="
                + trigger.name().toLowerCase(Locale.ROOT);
        dispatchConfigured(snapshot.unauthorizedPermissionCommands(), player, permission);
        record("unauthorized-permission", player, detail);
        plugin.getLogger().warning("Blocked unauthorized privileged permission on " + safePlayer(player)
                + " (" + detail + ")");
        if (snapshot.kickUnauthorizedPermission() && player.isOnline()) {
            player.kick(Component.text(snapshot.kickMessage()));
        }
    }

    private String protectedPermission(Player player, SecuritySettings snapshot) {
        for (PermissionPattern pattern : snapshot.protectedPermissions()) {
            if (player.hasPermission(pattern.source())) {
                return pattern.source();
            }
        }
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue() || (info.getAttachment() == null
                    && isGrantedToNonOperatorsByDefault(info.getPermission()))) {
                continue;
            }
            for (PermissionPattern pattern : snapshot.protectedPermissions()) {
                if (pattern.matches(info.getPermission())) {
                    return info.getPermission();
                }
            }
        }
        return null;
    }

    private static boolean isGrantedToNonOperatorsByDefault(String node) {
        Permission permission = Bukkit.getPluginManager().getPermission(node);
        if (permission == null) {
            return false;
        }
        PermissionDefault value = permission.getDefault();
        return value == PermissionDefault.TRUE || value == PermissionDefault.NOT_OP;
    }

    private void dispatchConfigured(List<String> commands, Player player, String permission) {
        if (commands.isEmpty()) {
            return;
        }
        String playerName = safePlayer(player);
        String safePermission = safePermission(permission);
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            for (String template : commands) {
                String command = template.replace("[player]", playerName)
                        .replace("%player_name%", playerName)
                        .replace("[perm]", safePermission);
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "An enforcement command failed", exception);
                }
            }
        });
    }

    private void record(String action, Player player, String detail) {
        AuditLogger logger = audit.get();
        if (logger != null) {
            logger.record(action, safePlayer(player), player.getUniqueId().toString(), detail);
        }
    }

    private static String safePlayer(Player player) {
        String name = player.getName();
        return name.matches("[A-Za-z0-9_]{1,16}") ? name : player.getUniqueId().toString();
    }

    private static String safeOfflinePlayer(OfflinePlayer player) {
        String name = player.getName();
        return name != null && name.matches("[A-Za-z0-9_]{1,16}") ? name : player.getUniqueId().toString();
    }

    private static String safePermission(String permission) {
        if (permission == null) {
            return "unknown";
        }
        String clean = permission.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.*:-]", "?");
        return clean.substring(0, Math.min(clean.length(), 128));
    }
}
