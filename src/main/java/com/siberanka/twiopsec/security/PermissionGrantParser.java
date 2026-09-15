package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recognizes positive permission mutations without evaluating arbitrary expressions. */
public final class PermissionGrantParser {
    private static final Set<String> POSITIVE_ACTIONS = Set.of(
            "add", "addtemp", "enable", "give", "grant", "set", "settemp", "toggle"
    );
    private static final Set<String> REMOVAL_ACTIONS = Set.of(
            "clear", "delete", "deny", "disable", "remove", "removetemp", "revoke", "unset", "unsettemp"
    );
    private static final Set<String> USER_TYPES = Set.of("player", "players", "user", "users");
    private static final Set<String> GROUP_TYPES = Set.of("group", "groups");
    private static final Set<String> DIRECT_USER_ROOTS = Set.of("manuaddp", "manuaddtemp", "useraddpermission");
    private static final Set<String> DIRECT_GROUP_ROOTS = Set.of("mangaddp", "mangaddtemp", "groupaddpermission");

    private PermissionGrantParser() {
    }

    public static Attempt find(CommandParser.ParsedCommand command, SecuritySettings settings) {
        if (command == null || command.status() != CommandParser.ParseStatus.VALID) {
            return null;
        }
        List<String> args = command.arguments();
        if (DIRECT_USER_ROOTS.contains(command.label()) && args.size() >= 2) {
            return protectedAttempt(TargetType.USER, args.get(0), args.get(1), settings);
        }
        if (DIRECT_GROUP_ROOTS.contains(command.label()) && args.size() >= 2) {
            return protectedAttempt(TargetType.GROUP, args.get(0), args.get(1), settings);
        }
        if (!settings.privilegeCommandRoots().contains(command.label()) || args.isEmpty()) {
            return null;
        }

        Target target = locateTarget(args);
        Mutation mutation = target == null ? null : locateMutation(args, target.afterTargetIndex());
        if (mutation == null || !mutation.positive()) {
            return null;
        }
        for (int index = mutation.afterActionIndex(); index < args.size(); index++) {
            String candidate = normalizedPermission(args.get(index));
            if (candidate == null || isFalseValue(args, index)) {
                continue;
            }
            Attempt attempt = protectedAttempt(target.type(), target.name(), candidate, settings);
            if (attempt != null) {
                return attempt;
            }
        }
        return null;
    }

    private static Target locateTarget(List<String> args) {
        for (int index = 0; index + 1 < args.size(); index++) {
            String value = normalize(args.get(index));
            if (USER_TYPES.contains(value)) {
                return new Target(TargetType.USER, safeTarget(args.get(index + 1)), index + 2);
            }
            if (GROUP_TYPES.contains(value)) {
                return new Target(TargetType.GROUP, safeTarget(args.get(index + 1)), index + 2);
            }
        }
        return null;
    }

    private static Mutation locateMutation(List<String> args, int startIndex) {
        for (int index = startIndex; index < args.size(); index++) {
            String value = normalize(args.get(index));
            if (POSITIVE_ACTIONS.contains(value)) {
                return new Mutation(true, index + 1);
            }
            if (REMOVAL_ACTIONS.contains(value)) {
                return new Mutation(false, index + 1);
            }
        }
        return null;
    }

    private static boolean isFalseValue(List<String> args, int permissionIndex) {
        return permissionIndex + 1 < args.size() && normalize(args.get(permissionIndex + 1)).equals("false");
    }

    private static Attempt protectedAttempt(TargetType type, String target, String rawPermission,
                                              SecuritySettings settings) {
        String permission = normalizedPermission(rawPermission);
        if (target == null || permission == null || settings.protectedPattern(permission) == null) {
            return null;
        }
        return new Attempt(type, target, permission);
    }

    private static String normalizedPermission(String value) {
        String normalized = normalize(value);
        if (normalized.length() > 128 || !normalized.matches("\\*|[a-z0-9_:-]+(?:\\.[a-z0-9_:*?-]+)*")) {
            return null;
        }
        return normalized;
    }

    private static String safeTarget(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Za-z0-9_.:-]{1,64}") ? normalized : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum TargetType { USER, GROUP }

    public record Attempt(TargetType targetType, String target, String permission) {
    }

    private record Target(TargetType type, String name, int afterTargetIndex) {
    }

    private record Mutation(boolean positive, int afterActionIndex) {
    }
}
