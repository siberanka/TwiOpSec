package com.siberanka.twiopsec.config;

import com.siberanka.twiopsec.security.PermissionPattern;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record SecuritySettings(
        boolean enabled,
        boolean checkOnJoin,
        boolean checkOnCommand,
        boolean checkOnInteract,
        boolean checkOnChat,
        boolean periodicEnabled,
        int periodicSeconds,
        boolean requireOnlineOpTarget,
        boolean deopUnauthorized,
        boolean kickUnauthorizedOperator,
        boolean kickUnauthorizedPermission,
        String kickMessage,
        List<String> unauthorizedOperatorCommands,
        List<String> unauthorizedPermissionCommands,
        List<PermissionPattern> protectedPermissions,
        Set<String> privilegeCommandRoots,
        Map<UUID, TrustedIdentity> trustedOperators,
        Map<UUID, TrustedIdentity> trustedPermissionHolders,
        boolean blockRuntimeUnloadCommands,
        Set<String> runtimePluginManagerRoots,
        boolean auditLog,
        int auditQueueCapacity
) {
    public SecuritySettings {
        kickMessage = safeText(kickMessage, 512, "TwiOpSec: unauthorized elevated access.");
        unauthorizedOperatorCommands = List.copyOf(unauthorizedOperatorCommands);
        unauthorizedPermissionCommands = List.copyOf(unauthorizedPermissionCommands);
        protectedPermissions = List.copyOf(protectedPermissions);
        privilegeCommandRoots = privilegeCommandRoots.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        trustedOperators = Map.copyOf(trustedOperators);
        trustedPermissionHolders = Map.copyOf(trustedPermissionHolders);
        runtimePluginManagerRoots = runtimePluginManagerRoots.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        periodicSeconds = Math.max(1, Math.min(periodicSeconds, 3600));
        auditQueueCapacity = Math.max(128, Math.min(auditQueueCapacity, 65_536));
    }

    public boolean isTrustedOperator(UUID uuid) {
        return uuid != null && trustedOperators.containsKey(uuid);
    }

    public boolean isTrustedPermissionHolder(UUID uuid) {
        // A trusted operator is already authorized for the server's broadest privilege set.
        // Requiring the same UUID in both maps creates an unsafe configuration footgun where
        // an explicitly trusted operator is immediately kicked for inherited OP permissions.
        return uuid != null && (trustedOperators.containsKey(uuid) || trustedPermissionHolders.containsKey(uuid));
    }

    public TrustedIdentity operatorByName(String name) {
        return byName(trustedOperators, name);
    }

    private static TrustedIdentity byName(Map<UUID, TrustedIdentity> identities, String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return identities.values().stream()
                .filter(identity -> identity.normalizedName().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    public static <T> Map<UUID, T> indexByUuid(List<T> values, Function<T, UUID> key) {
        return values.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity(), (a, b) -> a));
    }

    private static String safeText(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String clean = value.replace('\r', ' ').replace('\n', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
