package com.siberanka.twiopsec.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads Bukkit command aliases without reflecting into the server command map. */
public final class ServerAliasLoader {
    private static final int MAX_ALIASES = 512;
    private static final int MAX_COMMANDS_PER_ALIAS = 16;
    private static final int MAX_COMMAND_LENGTH = 1024;

    private ServerAliasLoader() {
    }

    public static Map<String, List<String>> load(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize();
        Path file = root.resolve("commands.yml").normalize();
        if (!file.getParent().equals(root)) {
            throw new IOException("commands.yml resolved outside the server root");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }

        YamlConfiguration yaml = SecureYaml.load(file).configuration();
        Object rawAliases = yaml.get("aliases");
        if (rawAliases == null) {
            return Map.of();
        }
        ConfigurationSection aliases = yaml.getConfigurationSection("aliases");
        if (aliases == null) {
            throw new IOException("commands.yml aliases must be a mapping");
        }
        if (aliases.getKeys(false).size() > MAX_ALIASES) {
            throw new IOException("commands.yml contains too many aliases");
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String rawName : aliases.getKeys(false)) {
            String name = rawName.toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_.-]{1,128}")) {
                throw new IOException("Invalid command alias name in commands.yml");
            }
            Object value = aliases.get(rawName);
            List<String> commands = strings(value, name);
            if (commands.isEmpty() || commands.size() > MAX_COMMANDS_PER_ALIAS) {
                throw new IOException("Alias " + name + " has an unsafe command count");
            }
            if (result.putIfAbsent(name, List.copyOf(commands)) != null) {
                throw new IOException("Duplicate command alias after case normalization: " + name);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> strings(Object value, String alias) throws IOException {
        if (value == null) {
            throw new IOException("Alias " + alias + " has no command value");
        }
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        List<String> commands = new ArrayList<>(values.size());
        for (Object entry : values) {
            if (!(entry instanceof String command)) {
                throw new IOException("Alias " + alias + " must contain only command strings");
            }
            String stripped = command.strip();
            if (stripped.isEmpty() || stripped.length() > MAX_COMMAND_LENGTH
                    || stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0
                    || stripped.indexOf('\0') >= 0) {
                throw new IOException("Alias " + alias + " contains an unsafe command string");
            }
            commands.add(stripped);
        }
        return commands;
    }
}
