package com.siberanka.twiopsec.security;

import java.util.Locale;
import java.util.Objects;

/** Matches permission nodes without regex, avoiding regex injection and pathological patterns. */
public final class PermissionPattern {
    private final String value;
    private final boolean descendantPattern;
    private final boolean globalNode;

    private PermissionPattern(String value, boolean descendantPattern, boolean globalNode) {
        this.value = value;
        this.descendantPattern = descendantPattern;
        this.globalNode = globalNode;
    }

    public static PermissionPattern parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 128 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid permission pattern");
        }
        if (normalized.equals("*")) {
            return new PermissionPattern("*", false, true);
        }
        boolean descendants = normalized.endsWith(".**");
        String node = descendants ? normalized.substring(0, normalized.length() - 3) : normalized;
        int star = node.indexOf('*');
        if (star >= 0 && (!node.endsWith(".*") || star != node.length() - 1)) {
            throw new IllegalArgumentException("Only an exact trailing .* node or explicit .** descendants are supported: " + raw);
        }
        String validationNode = node.endsWith(".*") ? node.substring(0, node.length() - 2) : node;
        if (!validationNode.matches("[a-z0-9_:-]+(?:\\.[a-z0-9_:-]+)*")) {
            throw new IllegalArgumentException("Permission pattern contains an invalid node: " + raw);
        }
        return descendants
                ? new PermissionPattern(node + '.', true, false)
                : new PermissionPattern(node, false, false);
    }

    public boolean matches(String permission) {
        if (permission == null) {
            return false;
        }
        String candidate = permission.toLowerCase(Locale.ROOT);
        return descendantPattern ? candidate.startsWith(value) : candidate.equals(value);
    }

    public String source() {
        return descendantPattern ? value + "**" : value;
    }

    public boolean isGlobalNode() {
        return globalNode;
    }

    @Override
    public String toString() {
        return source();
    }
}
