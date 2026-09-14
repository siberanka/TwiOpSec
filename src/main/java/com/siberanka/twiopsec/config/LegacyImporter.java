package com.siberanka.twiopsec.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Transactional, idempotent importer for the public T2C-OPSecurity YAML schema. */
public final class LegacyImporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final List<String> LEGACY_FILES = List.of(
            "config.yml", "opWhitelist.yml", "permissionWhitelist.yml",
            "languages/english.yml", "languages/german.yml", "languages/norwegian.yml"
    );

    private final Path dataFolder;
    private final Path pluginsFolder;
    private final Consumer<String> warning;
    private final Clock clock;

    public LegacyImporter(Path dataFolder, Path pluginsFolder, Consumer<String> warning) {
        this(dataFolder, pluginsFolder, warning, Clock.systemUTC());
    }

    LegacyImporter(Path dataFolder, Path pluginsFolder, Consumer<String> warning, Clock clock) {
        this.dataFolder = dataFolder.toAbsolutePath().normalize();
        this.pluginsFolder = pluginsFolder.toAbsolutePath().normalize();
        this.warning = warning;
        this.clock = clock;
    }

    public ImportReport importLegacy(String sourceFolderName, boolean force) {
        try {
            Files.createDirectories(dataFolder);
            Path marker = dataFolder.resolve("migration-v1.yml");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                validateMarker(marker);
            }
            if (!force && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return new ImportReport(ImportReport.Status.ALREADY_IMPORTED, 0, 0, 0,
                        "validated migration-v1.yml exists");
            }
            Path source = resolveSource(sourceFolderName);
            if (source == null) {
                return ImportReport.notFound();
            }

            Path configFile = checkedYaml(source.resolve("config.yml"), true);
            Path opFile = checkedYaml(source.resolve("opWhitelist.yml"), true);
            Path permissionFile = checkedYaml(source.resolve("permissionWhitelist.yml"), true);
            SecureYaml.Document legacyConfigDocument = SecureYaml.load(configFile);
            SecureYaml.Document legacyOperatorsDocument = SecureYaml.load(opFile);
            SecureYaml.Document legacyPermissionsDocument = SecureYaml.load(permissionFile);
            YamlConfiguration legacyConfig = legacyConfigDocument.configuration();
            YamlConfiguration legacyOperators = legacyOperatorsDocument.configuration();
            YamlConfiguration legacyPermissions = legacyPermissionsDocument.configuration();
            requireSection(legacyOperators, "opWhitelist", "opWhitelist.yml");
            requireSection(legacyPermissions, "permissionWhitelist", "permissionWhitelist.yml");
            validateLegacyTypes(legacyConfig, legacyOperators, legacyPermissions);

            Path targetFile = dataFolder.resolve("config.yml");
            SecureYaml.Document targetDocument = SecureYaml.load(targetFile);
            SettingsLoader.loadAll(targetFile, warning);
            YamlConfiguration target = targetDocument.configuration();
            MergeCounts counts = merge(target, legacyConfig, legacyOperators, legacyPermissions);
            SettingsLoader.validateGenerated(target, warning);

            String timestamp = STAMP.format(Instant.now(clock));
            Path backup = uniqueBackup(timestamp);
            backupSnapshot(backup.resolve("config.yml"), legacyConfigDocument);
            backupSnapshot(backup.resolve("opWhitelist.yml"), legacyOperatorsDocument);
            backupSnapshot(backup.resolve("permissionWhitelist.yml"), legacyPermissionsDocument);
            backupOptionalFiles(source, backup);
            Path libSource = source.getParent().resolve("T2CodeLib");
            if (Files.isDirectory(libSource, LinkOption.NOFOLLOW_LINKS)) {
                Path libConfig = libSource.resolve("config.yml");
                SecureYaml.Document libraryDocument = optionalSnapshot(libConfig, libSource.toRealPath());
                if (libraryDocument != null) {
                    backupSnapshot(backup.resolve("T2CodeLib/config.yml"), libraryDocument);
                }
            }

            YamlConfiguration markerState = markerState(marker, source.getFileName().toString(), timestamp, Map.of(
                    "config.yml", legacyConfigDocument.sha256(),
                    "opWhitelist.yml", legacyOperatorsDocument.sha256(),
                    "permissionWhitelist.yml", legacyPermissionsDocument.sha256()), counts);
            commitConfigAndMarker(targetFile, target, targetDocument.bytes(), marker, markerState);
            return new ImportReport(ImportReport.Status.IMPORTED, counts.operators,
                    counts.permissionHolders, counts.permissions, "transaction committed");
        } catch (IOException | RuntimeException exception) {
            warning.accept("Legacy import failed safely: " + safeException(exception));
            return new ImportReport(ImportReport.Status.FAILED, 0, 0, 0, safeException(exception));
        }
    }

    private Path resolveSource(String sourceFolderName) throws IOException {
        if (sourceFolderName == null || !sourceFolderName.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IOException("Invalid legacy source folder name");
        }
        Path source = pluginsFolder.resolve(sourceFolderName).normalize();
        if (!source.getParent().equals(pluginsFolder) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path realPlugins = pluginsFolder.toRealPath();
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(realPlugins) || realSource.equals(realPlugins)) {
            throw new IOException("Legacy source escaped plugins folder");
        }
        return realSource;
    }

    private static Path checkedYaml(Path path, boolean required) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("Required legacy file is missing: " + path.getFileName());
            }
            return null;
        }
        long size = Files.size(path);
        if (size <= 0 || size > SecureYaml.MAX_BYTES) {
            throw new IOException("Legacy YAML size is outside safety limits: " + path.getFileName());
        }
        return path;
    }

    private MergeCounts merge(YamlConfiguration target, YamlConfiguration config,
                              YamlConfiguration operators, YamlConfiguration permissions) throws IOException {
        target.set("enforcement.enabled",
                operators.getBoolean("opWhitelist.enable", true)
                        || permissions.getBoolean("permissionWhitelist.enable", true));
        target.set("enforcement.check-on-join", config.getBoolean("check.onJoin.enable", true));
        target.set("enforcement.check-on-command", config.getBoolean("check.onCommand.enable", true));
        target.set("enforcement.check-on-interact", config.getBoolean("check.onInteract.enable", true));
        target.set("enforcement.check-on-chat", config.getBoolean("check.onChat.enable", true));
        target.set("enforcement.periodic-enabled", config.getBoolean("check.timer.enable", true));
        target.set("enforcement.periodic-seconds", clamp(config.getInt("check.timer.refreshInSec", 2), 1, 3600));
        target.set("enforcement.require-online-op-target",
                operators.getBoolean("opWhitelist.playerMustBeOnlineToOp", true));
        target.set("enforcement.deop-unauthorized",
                operators.getBoolean("opWhitelist.noOpPlayerDeop.enable", true));
        target.set("enforcement.kick-unauthorized-operator",
                operators.getBoolean("opWhitelist.noOpPlayerKick.enable", true));
        target.set("enforcement.kick-unauthorized-permission",
                permissions.getBoolean("permissionWhitelist.playerWithPermissionKick", true));
        target.set("enforcement.unauthorized-operator-commands", safeCommands(
                operators.getStringList("opWhitelist.customCommands.commands"),
                operators.getBoolean("opWhitelist.customCommands.enable", false)));
        target.set("enforcement.unauthorized-permission-commands", safeCommands(
                permissions.getStringList("permissionWhitelist.customCommands.commands"),
                permissions.getBoolean("permissionWhitelist.customCommands.enable", false)));

        LinkedHashSet<String> protectedPermissions = new LinkedHashSet<>(SettingsLoader.REQUIRED_PERMISSION_DEFAULTS);
        protectedPermissions.addAll(target.getStringList("protected-permissions"));
        protectedPermissions.addAll(permissions.getStringList("permissionWhitelist.permissions"));
        target.set("protected-permissions", new ArrayList<>(protectedPermissions));

        int opCount = mergeIdentities(target, "trusted.operators",
                operators.getConfigurationSection("opWhitelist.whitelist"));
        int permissionCount = mergeIdentities(target, "trusted.permission-holders",
                permissions.getConfigurationSection("permissionWhitelist.whitelist"));
        return new MergeCounts(opCount, permissionCount, protectedPermissions.size());
    }

    private int mergeIdentities(YamlConfiguration target, String targetPath, ConfigurationSection source)
            throws IOException {
        LinkedHashMap<UUID, TrustedIdentity> identities = readTargetIdentities(target.getConfigurationSection(targetPath));
        if (source != null) {
            for (String key : source.getKeys(false)) {
                Object rawUuidValue = source.get(key + ".uuid");
                if (rawUuidValue instanceof Number number && number.longValue() == 0L) {
                    // T2C ships an unquoted numeric all-zero placeholder in some configurations.
                    continue;
                }
                String rawUuid = source.getString(key + ".uuid", "");
                String name = source.getString(key + ".name", "").trim();
                if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                    throw new IOException("Invalid legacy player name at " + source.getCurrentPath() + '.' + key);
                }
                try {
                    UUID uuid = UUID.fromString(SettingsLoader.canonicalUuid(rawUuid));
                    if (uuid.getMostSignificantBits() != 0L || uuid.getLeastSignificantBits() != 0L) {
                        identities.putIfAbsent(uuid, new TrustedIdentity(uuid, name));
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid legacy UUID at " + source.getCurrentPath() + '.' + key, exception);
                }
            }
        }
        target.set(targetPath, null);
        int index = 1;
        for (TrustedIdentity identity : identities.values()) {
            String path = targetPath + ".identity-" + index++;
            target.set(path + ".name", identity.name());
            target.set(path + ".uuid", identity.uuid().toString());
        }
        return identities.size();
    }

    private static LinkedHashMap<UUID, TrustedIdentity> readTargetIdentities(ConfigurationSection section) {
        LinkedHashMap<UUID, TrustedIdentity> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(SettingsLoader.canonicalUuid(section.getString(key + ".uuid", "")));
                result.put(uuid, new TrustedIdentity(uuid, section.getString(key + ".name", "")));
            } catch (IllegalArgumentException ignored) {
                // A bad existing record must not make a legacy import trust it.
            }
        }
        return result;
    }

    private List<String> safeCommands(List<String> commands, boolean enabled) throws IOException {
        if (!enabled) {
            return List.of();
        }
        if (commands.size() > 16) {
            throw new IOException("Legacy enforcement command count exceeds 16");
        }
        List<String> result = new ArrayList<>();
        for (String command : commands) {
            if (command == null || command.isBlank() || command.length() > 256
                    || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0
                    || command.indexOf('\0') >= 0) {
                throw new IOException("Legacy enforcement command is outside safety limits");
            }
            result.add(command.startsWith("/") ? command.substring(1) : command);
        }
        return result;
    }

    private Path uniqueBackup(String timestamp) throws IOException {
        Path root = dataFolder.resolve("migration-backups");
        Files.createDirectories(root);
        for (int suffix = 0; suffix < 1000; suffix++) {
            String name = suffix == 0 ? timestamp : timestamp + '-' + suffix;
            Path candidate = root.resolve(name);
            try {
                return Files.createDirectory(candidate);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A distinct directory prevents a forced import from overwriting rollback evidence.
            }
        }
        throw new IOException("Could not allocate a unique migration backup directory");
    }

    private static void backupSnapshot(Path output, SecureYaml.Document document) throws IOException {
        SecureYaml.atomicWrite(output, document.bytes());
    }

    private static void backupOptionalFiles(Path source, Path backup) throws IOException {
        for (String relative : LEGACY_FILES.subList(3, LEGACY_FILES.size())) {
            SecureYaml.Document document = optionalSnapshot(source.resolve(relative), source);
            if (document != null) {
                backupSnapshot(backup.resolve(relative), document);
            }
        }
    }

    private static SecureYaml.Document optionalSnapshot(Path path, Path allowedRoot) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path realRoot = allowedRoot.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot) || realPath.equals(realRoot)) {
            throw new IOException("Optional migration file escaped its source directory: " + path.getFileName());
        }
        return SecureYaml.load(realPath);
    }

    private static YamlConfiguration markerState(Path marker, String sourceName, String timestamp,
                                                 Map<String, String> hashes, MergeCounts counts) throws IOException {
        YamlConfiguration state = new YamlConfiguration();
        state.set("schema-version", 1);
        state.set("status", "committed");
        state.set("source-folder", sourceName);
        state.set("imported-at-utc", timestamp);
        state.set("source-sha256", hashes);
        state.set("counts.operators", counts.operators);
        state.set("counts.permission-holders", counts.permissionHolders);
        state.set("counts.protected-permissions", counts.permissions);
        SecureYaml.parse(state.saveToString(), marker.getFileName().toString());
        return state;
    }

    private static void commitConfigAndMarker(Path config, YamlConfiguration newConfig, byte[] originalConfig,
                                              Path marker, YamlConfiguration newMarker) throws IOException {
        byte[] originalMarker = null;
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Migration marker is not a regular file");
            }
            originalMarker = Files.readAllBytes(marker);
        }
        boolean configCommitted = false;
        try {
            SecureYaml.atomicWrite(config, newConfig.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            configCommitted = true;
            SecureYaml.atomicWrite(marker, newMarker.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            IOException failure = exception instanceof IOException ioException
                    ? ioException : new IOException("Migration commit failed", exception);
            if (configCommitted) {
                try {
                    SecureYaml.atomicWrite(config, originalConfig);
                    if (originalMarker == null) {
                        Files.deleteIfExists(marker);
                    } else {
                        SecureYaml.atomicWrite(marker, originalMarker);
                    }
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private static void validateMarker(Path marker) throws IOException {
        YamlConfiguration state = SecureYaml.load(marker).configuration();
        if (state.getInt("schema-version", -1) != 1 || !"committed".equals(state.getString("status"))) {
            throw new IOException("Migration marker is not a committed schema-version 1 document");
        }
        for (String name : List.of("config.yml", "opWhitelist.yml", "permissionWhitelist.yml")) {
            String hash = state.getString("source-sha256." + name, "");
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Migration marker has an invalid source hash for " + name);
            }
        }
    }

    private static void requireSection(YamlConfiguration yaml, String path, String sourceName) throws IOException {
        if (!yaml.isConfigurationSection(path)) {
            throw new IOException(sourceName + " is missing required section " + path);
        }
    }

    private static void validateLegacyTypes(YamlConfiguration config, YamlConfiguration operators,
                                            YamlConfiguration permissions) throws IOException {
        for (String path : List.of("check.onJoin.enable", "check.onCommand.enable", "check.onInteract.enable",
                "check.onChat.enable", "check.timer.enable")) {
            requireOptionalType(config, path, Boolean.class);
        }
        requireOptionalInteger(config, "check.timer.refreshInSec");
        for (String path : List.of("opWhitelist.enable", "opWhitelist.playerMustBeOnlineToOp",
                "opWhitelist.noOpPlayerDeop.enable", "opWhitelist.noOpPlayerKick.enable",
                "opWhitelist.customCommands.enable")) {
            requireOptionalType(operators, path, Boolean.class);
        }
        requireOptionalStringList(operators, "opWhitelist.customCommands.commands");
        validateLegacyIdentities(operators, "opWhitelist.whitelist");
        for (String path : List.of("permissionWhitelist.enable", "permissionWhitelist.playerWithPermissionKick",
                "permissionWhitelist.customCommands.enable")) {
            requireOptionalType(permissions, path, Boolean.class);
        }
        requireOptionalStringList(permissions, "permissionWhitelist.permissions");
        requireOptionalStringList(permissions, "permissionWhitelist.customCommands.commands");
        validateLegacyIdentities(permissions, "permissionWhitelist.whitelist");
    }

    private static void validateLegacyIdentities(YamlConfiguration yaml, String path) throws IOException {
        Object raw = yaml.get(path);
        if (raw == null) {
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            throw new IOException(path + " must be a mapping");
        }
        if (section.getKeys(false).size() > 4096) {
            throw new IOException(path + " contains too many identities");
        }
        for (String key : section.getKeys(false)) {
            if (!section.isConfigurationSection(key)) {
                throw new IOException(path + '.' + key + " must be a mapping");
            }
            requireOptionalType(section, key + ".name", String.class);
            Object uuid = section.get(key + ".uuid");
            if (!(uuid instanceof String) && !(uuid instanceof Number number && number.longValue() == 0L)) {
                throw new IOException(path + '.' + key + ".uuid must be text or the legacy zero placeholder");
            }
        }
    }

    private static void requireOptionalStringList(ConfigurationSection section, String path) throws IOException {
        Object raw = section.get(path);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IOException(path + " must contain only strings");
        }
    }

    private static void requireOptionalInteger(ConfigurationSection section, String path) throws IOException {
        Object raw = section.get(path);
        if (raw != null && !(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long)) {
            throw new IOException(path + " must be an integer");
        }
    }

    private static void requireOptionalType(ConfigurationSection section, String path, Class<?> type)
            throws IOException {
        Object raw = section.get(path);
        if (raw != null && !type.isInstance(raw)) {
            throw new IOException(path + " must be " + type.getSimpleName());
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String safeException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        return clean.substring(0, Math.min(clean.length(), 240));
    }

    private record MergeCounts(int operators, int permissionHolders, int permissions) {
    }
}
