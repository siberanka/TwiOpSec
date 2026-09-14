package com.siberanka.twiopsec.security;

import java.util.Locale;
import java.util.Objects;

/** Matches permission nodes without regex, avoiding regex injection and pathological patterns. */
public final class PermissionPattern {
    private final String value;
    private final boolean wildcard;
    private final boolean globalNode;

    private PermissionPattern(String value, boolean wildcard, boolean globalNode) {
        this.value = value;
        this.wildcard = wildcard;
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
        int star = normalized.indexOf('*');
        if (star >= 0 && (star != normalized.length() - 1 || !normalized.endsWith(".*"))) {
            throw new IllegalArgumentException("Only a trailing .* wildcard is supported: " + raw);
        }
        String node = star < 0 ? normalized : normalized.substring(0, normalized.length() - 2);
        if (!node.matches("[a-z0-9_:-]+(?:\\.[a-z0-9_:-]+)*")) {
            throw new IllegalArgumentException("Permission pattern contains an invalid node: " + raw);
        }
        return star < 0
                ? new PermissionPattern(normalized, false, false)
                : new PermissionPattern(node + '.', true, false);
    }

    public boolean matches(String permission) {
        if (permission == null) {
            return false;
        }
        String candidate = permission.toLowerCase(Locale.ROOT);
        return wildcard ? candidate.startsWith(value) : candidate.equals(value);
    }

    public String source() {
        return wildcard ? value + "*" : value;
    }

    public boolean isGlobalNode() {
        return globalNode;
    }

    @Override
    public String toString() {
        return source();
    }
}
