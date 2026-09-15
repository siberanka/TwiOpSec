package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PermissionGrantParserTest {
    @TempDir
    Path temporary;

    @Test
    void detectsLuckPermsPexAndGroupManagerPositiveProtectedGrants() throws IOException {
        SecuritySettings settings = settings();
        assertAttempt("lp user BadActor permission set luckperms.* true", "BadActor", "luckperms.*", settings);
        assertAttempt("luckperms group admin permission set minecraft.command.op", "admin",
                "minecraft.command.op", settings);
        assertAttempt("pex user BadActor add essentials.gamemode", "BadActor", "essentials.gamemode", settings);
        assertAttempt("manuaddp BadActor bukkit.command.plugins", "BadActor", "bukkit.command.plugins", settings);
        assertAttempt("mangaddp admin paper.command.reload", "admin", "paper.command.reload", settings);
        assertAttempt("lp user remove permission set minecraft.command.op true", "remove",
                "minecraft.command.op", settings);
        assertAttempt("lp user BadActor permission set luckperms.* true server=remove", "BadActor",
                "luckperms.*", settings);
    }

    @Test
    void permitsRemovalNegativeAndUnprotectedMutations() throws IOException {
        SecuritySettings settings = settings();
        assertNull(attempt("lp user BadActor permission unset luckperms.*", settings));
        assertNull(attempt("lp user BadActor permission set luckperms.* false", settings));
        assertNull(attempt("pex user BadActor remove essentials.*", settings));
        assertNull(attempt("lp user BadActor permission set example.harmless true", settings));
    }

    private void assertAttempt(String raw, String target, String permission, SecuritySettings settings) {
        PermissionGrantParser.Attempt attempt = attempt(raw, settings);
        assertNotNull(attempt);
        assertEquals(target, attempt.target());
        assertEquals(permission, attempt.permission());
    }

    private static PermissionGrantParser.Attempt attempt(String raw, SecuritySettings settings) {
        return PermissionGrantParser.find(CommandParser.parse(raw), settings);
    }

    private SecuritySettings settings() throws IOException {
        Path config = temporary.resolve("config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.save(config.toFile());
        return SettingsLoader.load(config.toFile(), ignored -> {
        });
    }
}
