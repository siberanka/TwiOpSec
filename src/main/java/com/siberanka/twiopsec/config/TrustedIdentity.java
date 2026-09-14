package com.siberanka.twiopsec.config;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A UUID-backed trusted identity. The name is retained only for audit and migration. */
public record TrustedIdentity(UUID uuid, String name) {
    public TrustedIdentity {
        Objects.requireNonNull(uuid, "uuid");
        name = name == null ? "" : name.trim();
    }

    public String normalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }
}
