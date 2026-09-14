package com.siberanka.twiopsec.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandParser {
    private static final Set<String> PLUGIN_MANAGERS = Set.of(
            "plugman", "plugmanx", "plm", "pluginmanager", "pm"
    );
    private static final Set<String> UNLOAD_ACTIONS = Set.of("disable", "reload", "unload", "restart");

    private CommandParser() {
    }

    public static ParsedCommand parse(String raw) {
        if (raw == null) {
            return ParsedCommand.EMPTY;
        }
        String command = raw.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank() || command.length() > 1024 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return ParsedCommand.EMPTY;
        }
        String[] split = command.split("\\s+");
        List<String> arguments = new ArrayList<>(split.length - 1);
        for (int i = 1; i < split.length; i++) {
            arguments.add(split[i]);
        }
        String rawLabel = split[0].toLowerCase(Locale.ROOT);
        int colon = rawLabel.lastIndexOf(':');
        String namespace = colon < 0 ? "" : rawLabel.substring(0, colon);
        String label = colon < 0 ? rawLabel : rawLabel.substring(colon + 1);
        return new ParsedCommand(label, namespace, List.copyOf(arguments));
    }

    public static boolean attemptsRuntimeUnload(ParsedCommand command) {
        if (command.label().equals("reload")
                && (command.namespace().isEmpty() || command.namespace().equals("bukkit")
                || command.namespace().equals("spigot"))) {
            return true;
        }
        if (!PLUGIN_MANAGERS.contains(command.label()) || command.arguments().isEmpty()) {
            return false;
        }
        String action = command.arguments().getFirst().toLowerCase(Locale.ROOT);
        if (!UNLOAD_ACTIONS.contains(action)) {
            return false;
        }
        return command.arguments().stream().skip(1)
                .map(CommandParser::stripPunctuation)
                .anyMatch(value -> value.equals("twiopsec") || value.equals("all") || value.equals("*"));
    }

    public static String stripNamespace(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        return colon < 0 ? normalized : normalized.substring(colon + 1);
    }

    private static String stripPunctuation(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.*-]", "");
    }

    public record ParsedCommand(String label, String namespace, List<String> arguments) {
        private static final ParsedCommand EMPTY = new ParsedCommand("", "", List.of());
    }
}
