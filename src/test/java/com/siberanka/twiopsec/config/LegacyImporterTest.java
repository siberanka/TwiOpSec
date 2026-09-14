package com.siberanka.twiopsec.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImporterTest {
    @TempDir
    Path temporary;

    @Test
    void importsBothWhitelistsAndUnionsRequiredDefaultsTransactionally() throws IOException {
        Path plugins = temporary.resolve("plugins");
        Path source = plugins.resolve("T2C-OPSecurity");
        Path target = plugins.resolve("TwiOpSec");
        Files.createDirectories(source);
        copyDefaultConfig(target);
        write(source.resolve("config.yml"), """
                check:
                  onJoin:
                    enable: false
                  onCommand:
                    enable: true
                  onInteract:
                    enable: false
                  onChat:
                    enable: true
                  timer:
                    enable: false
                    refreshInSec: 7
                """);
        write(source.resolve("opWhitelist.yml"), """
                opWhitelist:
                  enable: true
                  playerMustBeOnlineToOp: true
                  noOpPlayerDeop:
                    enable: true
                  noOpPlayerKick:
                    enable: false
                  customCommands:
                    enable: false
                    commands: []
                  whitelist:
                    owner:
                      name: Owner_1
                      uuid: 123456781234123412341234567890ab
                    placeholder:
                      name: player1
                      uuid: 00000000000000000000000000000000
                """);
        write(source.resolve("permissionWhitelist.yml"), """
                permissionWhitelist:
                  enable: true
                  playerWithPermissionKick: true
                  permissions:
                    - custom.super
                    - minecraft.command.op
                  customCommands:
                    enable: true
                    commands:
                      - lp user [player] permission unset [perm]
                  whitelist:
                    admin:
                      name: Admin_1
                      uuid: abcdefabcdefabcdefabcdefabcdefab
                """);

        LegacyImporter importer = new LegacyImporter(target, plugins, message -> {
        }, Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC));
        ImportReport report = importer.importLegacy("T2C-OPSecurity", false);

        assertEquals(ImportReport.Status.IMPORTED, report.status());
        assertEquals(1, report.operators());
        assertEquals(1, report.permissionHolders());
        YamlConfiguration output = YamlConfiguration.loadConfiguration(target.resolve("config.yml").toFile());
        assertFalse(output.getBoolean("enforcement.check-on-join"));
        assertFalse(output.getBoolean("enforcement.check-on-interact"));
        assertTrue(output.getBoolean("enforcement.check-on-chat"));
        assertFalse(output.getBoolean("enforcement.periodic-enabled"));
        assertEquals(7, output.getInt("enforcement.periodic-seconds"));
        assertTrue(output.getStringList("protected-permissions").containsAll(SettingsLoader.REQUIRED_PERMISSION_DEFAULTS));
        assertTrue(output.getStringList("protected-permissions").contains("custom.super"));
        assertEquals("12345678-1234-1234-1234-1234567890ab",
                output.getString("trusted.operators.identity-1.uuid"));
        assertTrue(Files.isRegularFile(target.resolve("migration-v1.yml")));
        assertTrue(Files.isRegularFile(target.resolve("migration-backups/20260914-120000/opWhitelist.yml")));
        assertEquals(ImportReport.Status.ALREADY_IMPORTED,
                importer.importLegacy("T2C-OPSecurity", false).status());
    }

    @Test
    void missingRequiredFileLeavesTargetByteForByteUntouched() throws IOException {
        Path plugins = temporary.resolve("plugins");
        Path source = plugins.resolve("T2C-OPSecurity");
        Path target = plugins.resolve("TwiOpSec");
        Files.createDirectories(source);
        copyDefaultConfig(target);
        write(source.resolve("config.yml"), "check: {}\n");
        write(source.resolve("opWhitelist.yml"), "opWhitelist: {}\n");
        byte[] before = Files.readAllBytes(target.resolve("config.yml"));

        ImportReport report = new LegacyImporter(target, plugins, message -> {
        }).importLegacy("T2C-OPSecurity", false);

        assertEquals(ImportReport.Status.FAILED, report.status());
        assertEquals(java.util.HexFormat.of().formatHex(before),
                java.util.HexFormat.of().formatHex(Files.readAllBytes(target.resolve("config.yml"))));
        assertFalse(Files.exists(target.resolve("migration-v1.yml")));
    }

    private static void copyDefaultConfig(Path target) throws IOException {
        Files.createDirectories(target);
        try (InputStream input = LegacyImporterTest.class.getResourceAsStream("/config.yml")) {
            Files.copy(java.util.Objects.requireNonNull(input), target.resolve("config.yml"));
        }
    }

    private static void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }
}
