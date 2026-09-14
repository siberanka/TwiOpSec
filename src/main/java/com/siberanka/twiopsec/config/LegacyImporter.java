package com.siberanka.twiopsec.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Transactional, idempotent importer for the public T2C-OPSecurity YAML schema. */
public final class LegacyImporter {
    private static final long MAX_YAML_BYTES = 2L * 1024L * 1024L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
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
            if (!force && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return new ImportReport(ImportReport.Status.ALREADY_IMPORTED, 0, 0, 0,
                        "migration-v1.yml exists");
            }
            Path source = resolveSource(sourceFolderName);
            if (source == null) {
                return ImportReport.notFound();
            }

            Path configFile = checkedYaml(source.resolve("config.yml"), true);
            Path opFile = checkedYaml(source.resolve("opWhitelist.yml"), true);
            Path permissionFile = checkedYaml(source.resolve("permissionWhitelist.yml"), true);
            YamlConfiguration legacyConfig = load(configFile);
            YamlConfiguration legacyOperators = load(opFile);
            YamlConfiguration legacyPermissions = load(permissionFile);

            File targetFile = dataFolder.resolve("config.yml").toFile();
            YamlConfiguration target = YamlConfiguration.loadConfiguration(targetFile);
            MergeCounts counts = merge(target, legacyConfig, legacyOperators, legacyPermissions);

            String timestamp = STAMP.format(Instant.now(clock));
            Path backup = dataFolder.resolve("migration-backups").resolve(timestamp);
            backupFiles(source, backup);
            Path libSource = source.getParent().resolve("T2CodeLib");
            if (Files.isDirectory(libSource, LinkOption.NOFOLLOW_LINKS)) {
                Path libConfig = libSource.resolve("config.yml");
                if (isSafeRegularYaml(libConfig)) {
                    Files.createDirectories(backup.resolve("T2CodeLib"));
                    Files.copy(libConfig, backup.resolve("T2CodeLib/config.yml"),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }

            atomicSave(target, targetFile.toPath());
            writeMarker(marker, source.getFileName().toString(), timestamp, Map.of(
                    "config.yml", sha256(configFile),
                    "opWhitelist.yml", sha256(opFile),
                    "permissionWhitelist.yml", sha256(permissionFile)
            ), counts);
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
        if (size <= 0 || size > MAX_YAML_BYTES) {
            throw new IOException("Legacy YAML size is outside safety limits: " + path.getFileName());
        }
        return path;
    }

    private static boolean isSafeRegularYaml(Path path) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.size(path) > 0 && Files.size(path) <= MAX_YAML_BYTES;
    }

    private static YamlConfiguration load(Path path) {
        return path == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(path.toFile());
    }

    private MergeCounts merge(YamlConfiguration target, YamlConfiguration config,
                              YamlConfiguration operators, YamlConfiguration permissions) {
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

    private int mergeIdentities(YamlConfiguration target, String targetPath, ConfigurationSection source) {
        LinkedHashMap<UUID, TrustedIdentity> identities = readTargetIdentities(target.getConfigurationSection(targetPath));
        if (source != null) {
            for (String key : source.getKeys(false)) {
                String rawUuid = source.getString(key + ".uuid", "");
                String name = source.getString(key + ".name", "").trim();
                if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(SettingsLoader.canonicalUuid(rawUuid));
                    if (uuid.getMostSignificantBits() != 0L || uuid.getLeastSignificantBits() != 0L) {
                        identities.putIfAbsent(uuid, new TrustedIdentity(uuid, name));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Invalid and upstream placeholder identities are deliberately not trusted.
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

    private List<String> safeCommands(List<String> commands, boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String command : commands) {
            if (result.size() == 16) {
                break;
            }
            if (command != null && !command.isBlank() && command.length() <= 256
                    && command.indexOf('\r') < 0 && command.indexOf('\n') < 0) {
                result.add(command.startsWith("/") ? command.substring(1) : command);
            }
        }
        return result;
    }

    private static void backupFiles(Path source, Path backup) throws IOException {
        for (String relative : LEGACY_FILES) {
            Path input = source.resolve(relative).normalize();
            if (!input.startsWith(source) || !isSafeRegularYaml(input)) {
                continue;
            }
            Path output = backup.resolve(relative);
            Files.createDirectories(output.getParent());
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void writeMarker(Path marker, String sourceName, String timestamp,
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
        atomicSave(state, marker);
    }

    private static void atomicSave(YamlConfiguration yaml, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream hashing = new DigestInputStream(input, digest)) {
                hashing.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
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
