package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.SecuritySettings;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.user.UserLoadEvent;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Native LuckPerms mutation and persistence integration. */
final class LuckPermsHook {
    private final Logger logger;
    private final LuckPerms api;
    private final AtomicReference<SecuritySettings> settings;
    private final AtomicReference<AuditLogger> audit;

    private LuckPermsHook(Logger logger, LuckPerms api, AtomicReference<SecuritySettings> settings,
                          AtomicReference<AuditLogger> audit) {
        this.logger = logger;
        this.api = api;
        this.settings = settings;
        this.audit = audit;
    }

    static LuckPermsHook create(TwiOpSecPlugin plugin, AtomicReference<SecuritySettings> settings,
                                AtomicReference<AuditLogger> audit) {
        RegisteredServiceProvider<LuckPerms> registration =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (registration == null) {
            throw new IllegalStateException("LuckPerms service is unavailable");
        }
        LuckPermsHook hook = new LuckPermsHook(plugin.getLogger(), registration.getProvider(), settings, audit);
        hook.api.getEventBus().subscribe(plugin, NodeAddEvent.class, hook::onNodeAdd);
        hook.api.getEventBus().subscribe(plugin, UserLoadEvent.class, event -> hook.remediateUser(event.getUser()));
        return hook;
    }

    static LuckPermsHook forTesting(Logger logger, LuckPerms api, AtomicReference<SecuritySettings> settings,
                                   AtomicReference<AuditLogger> audit) {
        return new LuckPermsHook(logger, api, settings, audit);
    }

    void reconcileLoadedData() {
        for (Group group : api.getGroupManager().getLoadedGroups()) {
            removeProtected(group, false, "startup-group-scan");
        }
        for (User user : api.getUserManager().getLoadedUsers()) {
            remediateUser(user);
        }
    }

    boolean removeDirectProtectedNodes(UUID uuid) {
        User user = api.getUserManager().getUser(uuid);
        return user != null && removeProtected(user, settings.get().isTrustedPermissionHolder(uuid), "player-check");
    }

    void onNodeAdd(NodeAddEvent event) {
        SecuritySettings snapshot = settings.get();
        Node node = event.getNode();
        if (!snapshot.permissionRemediationEnabled() || !snapshot.luckPermsNativeHook()
                || node.getType() != NodeType.PERMISSION || !node.getValue()
                || snapshot.protectedPattern(node.getKey()) == null) {
            return;
        }
        PermissionHolder target = event.getTarget();
        if (target instanceof User user && snapshot.isTrustedPermissionHolder(user.getUniqueId())) {
            return;
        }
        DataType dataType = event.getDataType();
        if (target.getData(dataType).remove(node).wasSuccessful()) {
            if (dataType == DataType.NORMAL) {
                save(target);
            }
            record(target, node.getKey(), "node-add-event:" + dataType.name().toLowerCase(Locale.ROOT));
        }
    }

    private void remediateUser(User user) {
        removeProtected(user, settings.get().isTrustedPermissionHolder(user.getUniqueId()), "user-load-event");
    }

    private boolean removeProtected(PermissionHolder holder, boolean trusted, String source) {
        SecuritySettings snapshot = settings.get();
        if (trusted || !snapshot.permissionRemediationEnabled() || !snapshot.luckPermsNativeHook()) {
            return false;
        }
        List<Node> removed = new ArrayList<>();
        boolean enduringChanged = false;
        for (DataType dataType : DataType.values()) {
            NodeMap data = holder.getData(dataType);
            for (Node node : List.copyOf(data.toCollection())) {
                if (node.getType() == NodeType.PERMISSION && node.getValue()
                        && snapshot.protectedPattern(node.getKey()) != null
                        && data.remove(node).wasSuccessful()) {
                    removed.add(node);
                    enduringChanged |= dataType == DataType.NORMAL;
                }
            }
        }
        if (removed.isEmpty()) {
            return false;
        }
        if (enduringChanged) {
            save(holder);
        }
        for (Node node : removed) {
            record(holder, node.getKey(), source);
        }
        return true;
    }

    private void save(PermissionHolder holder) {
        try {
            CompletableFuture<Void> saved = holder instanceof User user
                    ? api.getUserManager().saveUser(user)
                    : holder instanceof Group group ? api.getGroupManager().saveGroup(group) : null;
            if (saved != null) {
                saved.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        persistenceFailed(holder);
                    }
                });
            }
        } catch (RuntimeException ignored) {
            persistenceFailed(holder);
        }
    }

    private void persistenceFailed(PermissionHolder holder) {
        logger.severe("LuckPerms protected-permission removal could not be persisted; "
                + "in-memory enforcement remains active. Check permission storage health before restarting.");
        AuditLogger current = audit.get();
        if (current != null) {
            current.record("protected-permission-persist-failed", "server", "",
                    holder instanceof User ? "target-type=user" : "target-type=group");
        }
    }

    private void record(PermissionHolder holder, String permission, String source) {
        AuditLogger logger = audit.get();
        if (logger == null) {
            return;
        }
        if (holder instanceof User user) {
            logger.record("protected-permission-auto-unset", safe(user.getUsername()),
                    user.getUniqueId().toString(), "permission=" + safe(permission) + ",source=" + source);
        } else if (holder instanceof Group group) {
            logger.record("protected-group-permission-auto-unset", safe(group.getName()), "",
                    "permission=" + safe(permission) + ",source=" + source);
        }
    }

    private static String safe(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.*:-]", "?");
        return clean.substring(0, Math.min(clean.length(), 128));
    }
}
