package com.siberanka.twiopsec.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionLegacyImportTest {
    @TempDir
    Path temporary;

    @Test
    @EnabledIfSystemProperty(named = "twiopsec.legacyT2Dir", matches = ".+")
    void importsAuthorizedProductionConfigurationWithoutDroppingIdentitiesOrDefaults() throws IOException {
        Path original = Path.of(System.getProperty("twiopsec.legacyT2Dir")).toRealPath();
        Path plugins = temporary.resolve("plugins");
        Path staged = plugins.resolve("T2C-OPSecurity");
        Path target = plugins.resolve("TwiOpSec");
        Files.createDirectories(staged);
        for (String file : Set.of("config.yml", "opWhitelist.yml", "permissionWhitelist.yml")) {
            Files.copy(original.resolve(file), staged.resolve(file), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.createDirectories(target);
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(Objects.requireNonNull(input), target.resolve("config.yml"));
        }

        Set<String> expectedOperators = uuidSet(original.resolve("opWhitelist.yml"), "opWhitelist.whitelist");
        Set<String> expectedPermissionHolders = uuidSet(original.resolve("permissionWhitelist.yml"),
                "permissionWhitelist.whitelist");
        ImportReport report = new LegacyImporter(target, plugins, message -> {
        }).importLegacy("T2C-OPSecurity", false);

        assertEquals(ImportReport.Status.IMPORTED, report.status());
        YamlConfiguration imported = YamlConfiguration.loadConfiguration(target.resolve("config.yml").toFile());
        assertEquals(expectedOperators, uuidSet(imported, "trusted.operators"));
        assertEquals(expectedPermissionHolders, uuidSet(imported, "trusted.permission-holders"));
        assertTrue(imported.getStringList("protected-permissions")
                .containsAll(SettingsLoader.REQUIRED_PERMISSION_DEFAULTS));
        assertEquals(new HashSet<>(YamlConfiguration.loadConfiguration(
                        original.resolve("permissionWhitelist.yml").toFile())
                        .getStringList("permissionWhitelist.permissions")),
                intersection(imported.getStringList("protected-permissions"),
                        YamlConfiguration.loadConfiguration(original.resolve("permissionWhitelist.yml").toFile())
                                .getStringList("permissionWhitelist.permissions")));
    }

    private static Set<String> uuidSet(Path file, String section) {
        return uuidSet(YamlConfiguration.loadConfiguration(file.toFile()), section);
    }

    private static Set<String> uuidSet(YamlConfiguration yaml, String sectionPath) {
        Set<String> result = new HashSet<>();
        ConfigurationSection section = yaml.getConfigurationSection(sectionPath);
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            try {
                String canonical = SettingsLoader.canonicalUuid(section.getString(key + ".uuid", ""));
                if (!canonical.equals("00000000-0000-0000-0000-000000000000")) {
                    result.add(canonical);
                }
            } catch (IllegalArgumentException ignored) {
                // Same invalid legacy identities intentionally ignored by production importer.
            }
        }
        return result;
    }

    private static Set<String> intersection(java.util.List<String> imported, java.util.List<String> legacy) {
        Set<String> result = new HashSet<>(imported);
        result.retainAll(new HashSet<>(legacy));
        return result;
    }
}
