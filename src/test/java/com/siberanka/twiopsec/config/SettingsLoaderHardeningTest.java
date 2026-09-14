package com.siberanka.twiopsec.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLoaderHardeningTest {
    private static final UUID TRUSTED = UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    @TempDir
    Path temporary;

    @Test
    void rejectsMalformedDuplicateTaggedAndMistypedYaml() throws IOException {
        assertRejected("schema-version: 1\ntrusted: [\n");
        assertRejected("schema-version: 1\nenforcement:\n  enabled: true\n  enabled: false\n");
        assertRejected("schema-version: 1\nvalue: !!java.net.URL [https://example.invalid]\n");
        assertRejected("schema-version: 1\nenforcement:\n  enabled: 'yes'\n");
        assertRejected("schema-version: 1\nprotected-permissions:\n  - 'bad permission'\n");
        assertRejected("schema-version: 1\ntrusted: []\n");
        assertRejected("schema-version: 1\ntrusted:\n  operators: []\n");
        assertRejected("schema-version: 1\ntrusted:\n  operators:\n    owner:\n      name: Owner_1\n      uuid: 42\n");
        assertRejected("schema-version: 1\nenforcement:\n  periodic-seconds: 0\n");
        assertRejected("schema-version: 1\nhardening:\n  audit-queue-capacity: 65537\n");
        assertRejected("schema-version: 1\nupdates:\n  enabled: 'yes'\n");
        assertRejected("schema-version: 1\nupdates:\n  connect-timeout-seconds: 1\n");
        assertRejected("schema-version: 1\nupdates:\n  request-timeout-seconds: 16\n");
    }

    @Test
    void mandatoryRootsSurviveEmptyListsAndTrustedOperatorImpliesPermissionTrust() throws IOException {
        Path config = temporary.resolve("safe.yml");
        Files.writeString(config, """
                schema-version: 1
                privilege-command-roots: []
                hardening:
                  block-runtime-unload-commands: false
                  runtime-plugin-manager-roots: []
                trusted:
                  operators:
                    owner:
                      name: Owner_1
                      uuid: 12345678-1234-1234-1234-1234567890ab
                """);

        SecuritySettings settings = SettingsLoader.load(config.toFile(), ignored -> {
        });

        assertTrue(settings.privilegeCommandRoots().containsAll(SettingsLoader.REQUIRED_PRIVILEGE_COMMAND_ROOTS));
        assertTrue(settings.runtimePluginManagerRoots().containsAll(
                com.siberanka.twiopsec.security.CommandParser.DEFAULT_PLUGIN_MANAGER_ROOTS));
        assertTrue(settings.blockRuntimeUnloadCommands());
        assertTrue(settings.isTrustedOperator(TRUSTED));
        assertTrue(settings.isTrustedPermissionHolder(TRUSTED));
    }

    @Test
    void rejectsUnsupportedSchemaAndExcessivePatternCounts() throws IOException {
        assertRejected("schema-version: 2\n");
        StringBuilder yaml = new StringBuilder("schema-version: 1\nprotected-permissions:\n");
        for (int index = 0; index < 513; index++) {
            yaml.append("  - custom.node").append(index).append('\n');
        }
        assertRejected(yaml.toString());
    }

    private void assertRejected(String yaml) throws IOException {
        Path config = temporary.resolve("rejected-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.writeString(config, yaml);
        assertThrows(IOException.class, () -> SettingsLoader.load(config.toFile(), ignored -> {
        }));
    }
}
