package com.siberanka.twiopsec.config;

import com.siberanka.twiopsec.security.CommandParser;
import com.siberanka.twiopsec.security.PermissionPattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class SettingsLoader {
    public static final List<String> REQUIRED_PERMISSION_DEFAULTS = List.of(
            "*", "minecraft.*", "minecraft.command.*", "minecraft.command.op",
            "bukkit.command.*", "paper.command.*", "essentials.*", "luckperms.*", "twiopsec.admin"
    );

    private SettingsLoader() {
    }

    public static SecuritySettings load(File file, Consumer<String> warning) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<PermissionPattern> patterns = new ArrayList<>();
        LinkedHashSet<String> patternSources = new LinkedHashSet<>(REQUIRED_PERMISSION_DEFAULTS);
        patternSources.addAll(yaml.getStringList("protected-permissions"));
        for (String source : patternSources) {
            try {
                patterns.add(PermissionPattern.parse(source));
            } catch (IllegalArgumentException exception) {
                warning.accept("Ignored invalid permission pattern: " + printable(source));
            }
        }

        Set<String> roots = new LinkedHashSet<>();
        for (String value : yaml.getStringList("privilege-command-roots")) {
            String root = normalizeRoot(value);
            if (root != null) {
                roots.add(root);
            } else {
                warning.accept("Ignored invalid command root");
            }
        }

        Set<String> pluginManagerRoots = new LinkedHashSet<>(CommandParser.DEFAULT_PLUGIN_MANAGER_ROOTS);
        for (String value : yaml.getStringList("hardening.runtime-plugin-manager-roots")) {
            String root = normalizeRoot(value);
            if (root != null) {
                pluginManagerRoots.add(root);
            } else {
                warning.accept("Ignored invalid runtime plugin-manager root");
            }
        }

        return new SecuritySettings(
                yaml.getBoolean("enforcement.enabled", true),
                yaml.getBoolean("enforcement.check-on-join", true),
                yaml.getBoolean("enforcement.check-on-command", true),
                yaml.getBoolean("enforcement.check-on-interact", true),
                yaml.getBoolean("enforcement.check-on-chat", true),
                yaml.getBoolean("enforcement.periodic-enabled", true),
                yaml.getInt("enforcement.periodic-seconds", 2),
                yaml.getBoolean("enforcement.require-online-op-target", true),
                yaml.getBoolean("enforcement.deop-unauthorized", true),
                yaml.getBoolean("enforcement.kick-unauthorized-operator", true),
                yaml.getBoolean("enforcement.kick-unauthorized-permission", true),
                yaml.getString("enforcement.kick-message", "TwiOpSec: unauthorized elevated access."),
                boundedCommands(yaml.getStringList("enforcement.unauthorized-operator-commands"), warning),
                boundedCommands(yaml.getStringList("enforcement.unauthorized-permission-commands"), warning),
                patterns,
                roots,
                identities(yaml.getConfigurationSection("trusted.operators"), warning),
                identities(yaml.getConfigurationSection("trusted.permission-holders"), warning),
                yaml.getBoolean("hardening.block-runtime-unload-commands", true),
                pluginManagerRoots,
                yaml.getBoolean("hardening.audit-log", true),
                yaml.getInt("hardening.audit-queue-capacity", 2048)
        );
    }

    private static Map<UUID, TrustedIdentity> identities(ConfigurationSection section, Consumer<String> warning) {
        List<TrustedIdentity> values = new ArrayList<>();
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(false)) {
            String rawUuid = section.getString(key + ".uuid", "");
            String name = section.getString(key + ".name", "");
            try {
                UUID uuid = UUID.fromString(canonicalUuid(rawUuid));
                if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
                    continue;
                }
                values.add(new TrustedIdentity(uuid, name));
            } catch (IllegalArgumentException exception) {
                warning.accept("Ignored trusted identity with invalid UUID at " + printable(key));
            }
        }
        return SecuritySettings.indexByUuid(values, TrustedIdentity::uuid);
    }

    static String canonicalUuid(String value) {
        String compact = value == null ? "" : value.trim().replace("-", "");
        if (!compact.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return compact.substring(0, 8) + '-' + compact.substring(8, 12) + '-' + compact.substring(12, 16)
                + '-' + compact.substring(16, 20) + '-' + compact.substring(20);
    }

    private static List<String> boundedCommands(List<String> source, Consumer<String> warning) {
        List<String> result = new ArrayList<>();
        for (String command : source) {
            if (command == null || command.isBlank() || command.length() > 256
                    || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0 || result.size() >= 16) {
                warning.accept("Ignored unsafe or excessive enforcement command");
                continue;
            }
            result.add(command.startsWith("/") ? command.substring(1) : command);
        }
        return result;
    }

    private static String normalizeRoot(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,64}") ? normalized : null;
    }

    private static String printable(String value) {
        if (value == null) {
            return "<null>";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "?");
        return clean.substring(0, Math.min(clean.length(), 80));
    }
}
