package com.siberanka.twiopsec.config;

import com.siberanka.twiopsec.security.CommandParser;
import com.siberanka.twiopsec.security.PermissionPattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
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
    public static final Set<String> REQUIRED_PRIVILEGE_COMMAND_ROOTS = Set.of(
            "lp", "luckperms", "lpb", "permissions", "pex"
    );
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PERMISSION_PATTERNS = 512;
    private static final int MAX_COMMAND_ROOTS = 128;
    private static final int MAX_IDENTITIES_PER_LIST = 4096;

    private SettingsLoader() {
    }

    public static SecuritySettings load(File file, Consumer<String> warning) throws IOException {
        return loadAll(file.toPath(), warning).settings();
    }

    public static LoadedConfiguration loadAll(Path file, Consumer<String> warning) throws IOException {
        SecureYaml.Document document = SecureYaml.load(file);
        LoadedConfiguration loaded = parse(document.configuration(), warning, document.text());
        return new LoadedConfiguration(loaded.settings(), loaded.automaticLegacyImport(),
                loaded.legacySourceFolder(), document.text());
    }

    public static void persistLastKnownGood(Path file, LoadedConfiguration loaded) throws IOException {
        SecureYaml.atomicWrite(file, loaded.sourceText().getBytes(StandardCharsets.UTF_8));
    }

    static LoadedConfiguration validateGenerated(YamlConfiguration yaml, Consumer<String> warning) throws IOException {
        String text = yaml.saveToString();
        SecureYaml.Document document = SecureYaml.parse(text, "generated-config.yml");
        return parse(document.configuration(), warning, document.text());
    }

    private static LoadedConfiguration parse(YamlConfiguration yaml, Consumer<String> warning, String sourceText)
            throws IOException {
        for (String section : List.of("enforcement", "trusted", "legacy-import", "hardening")) {
            requireOptionalSection(yaml, section);
        }
        int schema = integer(yaml, "schema-version", SCHEMA_VERSION);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("Unsupported config schema-version: " + schema);
        }

        List<PermissionPattern> patterns = new ArrayList<>();
        LinkedHashSet<String> patternSources = new LinkedHashSet<>(REQUIRED_PERMISSION_DEFAULTS);
        patternSources.addAll(stringList(yaml, "protected-permissions"));
        if (patternSources.size() > MAX_PERMISSION_PATTERNS) {
            throw new IOException("Too many protected-permissions entries; maximum is " + MAX_PERMISSION_PATTERNS);
        }
        for (String source : patternSources) {
            try {
                patterns.add(PermissionPattern.parse(source));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid permission pattern: " + printable(source), exception);
            }
        }

        Set<String> roots = normalizedRoots(stringList(yaml, "privilege-command-roots"),
                REQUIRED_PRIVILEGE_COMMAND_ROOTS, "privilege-command-roots");
        Set<String> pluginManagerRoots = normalizedRoots(
                stringList(yaml, "hardening.runtime-plugin-manager-roots"),
                CommandParser.DEFAULT_PLUGIN_MANAGER_ROOTS, "runtime plugin-manager roots");

        List<String> operatorCommands = boundedCommands(
                stringList(yaml, "enforcement.unauthorized-operator-commands"));
        List<String> permissionCommands = boundedCommands(
                stringList(yaml, "enforcement.unauthorized-permission-commands"));
        boolean enabled = bool(yaml, "enforcement.enabled", true);
        boolean checkOnJoin = bool(yaml, "enforcement.check-on-join", true);
        boolean checkOnCommand = bool(yaml, "enforcement.check-on-command", true);
        boolean periodicEnabled = bool(yaml, "enforcement.periodic-enabled", true);
        boolean deopUnauthorized = bool(yaml, "enforcement.deop-unauthorized", true);
        boolean kickUnauthorizedPermission = bool(yaml, "enforcement.kick-unauthorized-permission", true);
        boolean requestedRuntimeUnloadBlock = bool(yaml, "hardening.block-runtime-unload-commands", true);
        boolean blockRuntimeUnload = true;

        if (enabled && !checkOnJoin) {
            warning.accept("Security degradation: enforcement.check-on-join is disabled");
        }
        if (enabled && !checkOnCommand) {
            warning.accept("Security degradation: enforcement.check-on-command is disabled");
        }
        if (enabled && !periodicEnabled) {
            warning.accept("Security degradation: periodic enforcement is disabled");
        }
        if (enabled && !deopUnauthorized) {
            warning.accept("Security degradation: unauthorized operators will not be deopped automatically");
        }
        if (enabled && !kickUnauthorizedPermission && permissionCommands.isEmpty()) {
            warning.accept("Security degradation: permission violations have no configured removal action");
        }
        if (!requestedRuntimeUnloadBlock) {
            warning.accept("hardening.block-runtime-unload-commands=false is ignored; unload blocking remains active");
        }
        if (!bool(yaml, "legacy-import.preserve-default-permissions", true)) {
            warning.accept("legacy-import.preserve-default-permissions=false is ignored; mandatory defaults remain active");
        }

        SecuritySettings settings = new SecuritySettings(
                enabled,
                checkOnJoin,
                checkOnCommand,
                bool(yaml, "enforcement.check-on-interact", true),
                bool(yaml, "enforcement.check-on-chat", true),
                periodicEnabled,
                boundedInteger(yaml, "enforcement.periodic-seconds", 2, 1, 3600),
                bool(yaml, "enforcement.require-online-op-target", true),
                deopUnauthorized,
                bool(yaml, "enforcement.kick-unauthorized-operator", true),
                kickUnauthorizedPermission,
                string(yaml, "enforcement.kick-message", "TwiOpSec: unauthorized elevated access."),
                operatorCommands,
                permissionCommands,
                patterns,
                roots,
                identities(optionalSection(yaml, "trusted.operators"), "trusted.operators"),
                identities(optionalSection(yaml, "trusted.permission-holders"), "trusted.permission-holders"),
                blockRuntimeUnload,
                pluginManagerRoots,
                bool(yaml, "hardening.audit-log", true),
                boundedInteger(yaml, "hardening.audit-queue-capacity", 2048, 128, 65_536)
        );

        boolean automatic = bool(yaml, "legacy-import.automatic", true);
        String sourceFolder = string(yaml, "legacy-import.source-folder", "T2C-OPSecurity").trim();
        if (!sourceFolder.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IOException("Invalid legacy-import.source-folder");
        }
        return new LoadedConfiguration(settings, automatic, sourceFolder, sourceText);
    }

    private static Set<String> normalizedRoots(List<String> values, Set<String> mandatory, String description)
            throws IOException {
        LinkedHashSet<String> roots = new LinkedHashSet<>(mandatory);
        if (values.size() + roots.size() > MAX_COMMAND_ROOTS) {
            throw new IOException("Too many " + description + "; maximum is " + MAX_COMMAND_ROOTS);
        }
        for (String value : values) {
            String root = normalizeRoot(value);
            if (root == null) {
                throw new IOException("Invalid entry in " + description + ": " + printable(value));
            }
            roots.add(root);
        }
        return roots;
    }

    private static Map<UUID, TrustedIdentity> identities(ConfigurationSection section, String path)
            throws IOException {
        List<TrustedIdentity> values = new ArrayList<>();
        if (section == null) {
            return Map.of();
        }
        if (section.getKeys(false).size() > MAX_IDENTITIES_PER_LIST) {
            throw new IOException("Too many identities in " + path + "; maximum is " + MAX_IDENTITIES_PER_LIST);
        }
        for (String key : section.getKeys(false)) {
            if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IOException("Invalid identity key in " + path + ": " + printable(key));
            }
            ConfigurationSection identity = section.getConfigurationSection(key);
            if (identity == null) {
                throw new IOException("Identity at " + path + '.' + printable(key) + " must be a mapping");
            }
            Object rawUuidValue = identity.get("uuid");
            Object rawNameValue = identity.get("name");
            if (!(rawUuidValue instanceof String rawUuid) || !(rawNameValue instanceof String rawName)) {
                throw new IOException("Identity at " + path + '.' + printable(key)
                        + " must contain text name and uuid fields");
            }
            String name = rawName.trim();
            if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IOException("Invalid player name at " + path + '.' + printable(key));
            }
            try {
                UUID uuid = UUID.fromString(canonicalUuid(rawUuid));
                if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
                    throw new IOException("Nil UUID is not trusted at " + path + '.' + printable(key));
                }
                values.add(new TrustedIdentity(uuid, name));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid UUID at " + path + '.' + printable(key), exception);
            }
        }
        Map<UUID, TrustedIdentity> indexed = SecuritySettings.indexByUuid(values, TrustedIdentity::uuid);
        if (indexed.size() != values.size()) {
            throw new IOException("Duplicate UUID entries are not allowed in " + path);
        }
        return indexed;
    }

    static String canonicalUuid(String value) {
        String compact = value == null ? "" : value.trim().replace("-", "");
        if (!compact.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return compact.substring(0, 8) + '-' + compact.substring(8, 12) + '-' + compact.substring(12, 16)
                + '-' + compact.substring(16, 20) + '-' + compact.substring(20);
    }

    private static List<String> boundedCommands(List<String> source) throws IOException {
        if (source.size() > 16) {
            throw new IOException("Too many enforcement commands; maximum is 16");
        }
        List<String> result = new ArrayList<>();
        for (String command : source) {
            if (command.isBlank() || command.length() > 256
                    || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
                throw new IOException("Unsafe enforcement command in configuration");
            }
            result.add(command.startsWith("/") ? command.substring(1) : command);
        }
        return result;
    }

    private static List<String> stringList(YamlConfiguration yaml, String path) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IOException(path + " must be a list");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object value : list) {
            if (!(value instanceof String text)) {
                throw new IOException(path + " must contain only strings");
            }
            result.add(text);
        }
        return result;
    }

    private static boolean bool(YamlConfiguration yaml, String path, boolean fallback) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IOException(path + " must be true or false");
        }
        return value;
    }

    private static int integer(YamlConfiguration yaml, String path, int fallback) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IOException(path + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException(path + " is outside the supported integer range");
        }
        return (int) value;
    }

    private static int boundedInteger(YamlConfiguration yaml, String path, int fallback, int minimum, int maximum)
            throws IOException {
        int value = integer(yaml, path, fallback);
        if (value < minimum || value > maximum) {
            throw new IOException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void requireOptionalSection(YamlConfiguration yaml, String path) throws IOException {
        optionalSection(yaml, path);
    }

    private static ConfigurationSection optionalSection(YamlConfiguration yaml, String path) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return null;
        }
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            throw new IOException(path + " must be a mapping");
        }
        return section;
    }

    private static String string(YamlConfiguration yaml, String path, String fallback) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String value)) {
            throw new IOException(path + " must be text");
        }
        return value;
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

    public record LoadedConfiguration(SecuritySettings settings, boolean automaticLegacyImport,
                                      String legacySourceFolder, String sourceText) {
    }
}
