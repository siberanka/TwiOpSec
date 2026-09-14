package com.siberanka.twiopsec.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CommandParser {
    public static final Set<String> DEFAULT_PLUGIN_MANAGER_ROOTS = Set.of(
            "plugman", "plugmanx", "plm", "pluginmanager", "plugin-manager", "plugmanager",
            "plugincontrol", "plugincontroller", "serverutils", "serverutilities", "pm", "pman"
    );
    private static final Set<String> UNLOAD_ACTIONS = Set.of("disable", "reload", "unload", "restart");
    private static final Set<String> PREFIX_WRAPPERS = Set.of(
            "asconsole", "consolecommand", "runcommand", "dispatchcommand"
    );
    private static final Set<String> SUBJECT_WRAPPERS = Set.of("sudo", "runas");
    private static final int MAX_EMBEDDED_DEPTH = 8;

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
        if (command.isBlank()) {
            return ParsedCommand.EMPTY;
        }
        if (command.length() > 1024 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0
                || command.indexOf('\0') >= 0) {
            return ParsedCommand.INVALID;
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
        if (!label.matches("[a-z0-9_.-]{1,128}") || !namespace.matches("[a-z0-9_.-]{0,128}")) {
            return ParsedCommand.INVALID;
        }
        return new ParsedCommand(label, namespace, List.copyOf(arguments), ParseStatus.VALID);
    }

    /**
     * Returns the outer command and bounded command payloads exposed by common dispatch wrappers.
     * Vanilla execute/return use the {@code run} delimiter; common sudo-style plugins either place
     * the command first or after a subject. Slash-prefixed payloads are also inspected generically.
     */
    public static List<ParsedCommand> parseChain(String raw) {
        return parseChain(raw, Map.of(), "");
    }

    public static List<ParsedCommand> parseChain(String raw, Map<String, List<String>> aliases, String senderName) {
        LinkedHashSet<ParsedCommand> commands = new LinkedHashSet<>();
        collect(raw, commands, aliases, senderName, 0);
        return List.copyOf(commands);
    }

    private static void collect(String raw, Set<ParsedCommand> commands, Map<String, List<String>> aliases,
                                String senderName, int depth) {
        if (depth > MAX_EMBEDDED_DEPTH || commands.size() > MAX_EMBEDDED_DEPTH * 2) {
            commands.add(ParsedCommand.INVALID);
            return;
        }
        ParsedCommand command = parse(raw);
        if (!commands.add(command)) {
            commands.add(ParsedCommand.INVALID);
            return;
        }
        if (command.status() != ParseStatus.VALID) {
            return;
        }

        List<String> aliasCommands = aliases.get(command.label());
        if (aliasCommands != null) {
            for (String aliasCommand : aliasCommands) {
                String expanded = expandAlias(aliasCommand, command.arguments(), senderName);
                if (expanded == null) {
                    commands.add(ParsedCommand.INVALID);
                } else {
                    collect(expanded, commands, aliases, senderName, depth + 1);
                }
            }
        }

        List<String> arguments = command.arguments();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equalsIgnoreCase("run") && index + 1 < arguments.size()) {
                collect(String.join(" ", arguments.subList(index + 1, arguments.size())), commands, aliases,
                        senderName, depth + 1);
            } else if (argument.startsWith("/") && argument.length() > 1) {
                collect(String.join(" ", arguments.subList(index, arguments.size())), commands, aliases,
                        senderName, depth + 1);
            }
        }
        if (PREFIX_WRAPPERS.contains(command.label()) && !arguments.isEmpty()) {
            collect(String.join(" ", arguments), commands, aliases, senderName, depth + 1);
        } else if (SUBJECT_WRAPPERS.contains(command.label()) && arguments.size() > 1) {
            collect(String.join(" ", arguments.subList(1, arguments.size())), commands, aliases, senderName,
                    depth + 1);
        }
    }

    /** Mirrors Bukkit FormattedCommandAlias replacement rules without reflective access. */
    private static String expandAlias(String format, List<String> arguments, String senderName) {
        String source = format.replace("$sender", senderName);
        StringBuilder result = new StringBuilder(source.length());
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (current == '\\' && index + 1 < source.length() && source.charAt(index + 1) == '$') {
                result.append('$');
                index += 2;
                continue;
            }
            if (current != '$') {
                result.append(current);
                index++;
                continue;
            }

            index++;
            boolean required = index < source.length() && source.charAt(index) == '$';
            if (required) {
                index++;
            }
            int digitsStart = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
            if (digitsStart == index) {
                return null;
            }
            int number;
            try {
                number = Integer.parseInt(source.substring(digitsStart, index));
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (number <= 0) {
                return null;
            }
            int argumentIndex = number - 1;
            boolean remainder = index < source.length() && source.charAt(index) == '-';
            if (remainder) {
                index++;
            }
            if (required && argumentIndex >= arguments.size()) {
                return null;
            }
            if (argumentIndex < arguments.size()) {
                result.append(remainder
                        ? String.join(" ", arguments.subList(argumentIndex, arguments.size()))
                        : arguments.get(argumentIndex));
            }
            if (result.length() > 1024) {
                return null;
            }
        }
        return result.toString().strip();
    }

    public static boolean attemptsRuntimeUnload(ParsedCommand command) {
        return attemptsRuntimeUnload(command, DEFAULT_PLUGIN_MANAGER_ROOTS);
    }

    public static boolean attemptsRuntimeUnload(ParsedCommand command, Set<String> pluginManagerRoots) {
        if (command.label().equals("reload")
                && (command.namespace().isEmpty() || command.namespace().equals("bukkit")
                || command.namespace().equals("spigot"))) {
            return true;
        }
        if (!pluginManagerRoots.contains(command.label()) || command.arguments().isEmpty()) {
            return false;
        }
        for (int index = 0; index < command.arguments().size(); index++) {
            String action = stripPunctuation(command.arguments().get(index));
            if (UNLOAD_ACTIONS.contains(action) && command.arguments().stream().skip(index + 1L)
                    .map(CommandParser::canonicalTarget)
                    .anyMatch(CommandParser::isProtectedTarget)) {
                return true;
            }
        }
        return false;
    }

    public static String stripNamespace(String label) {
        String normalized = label.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        return colon < 0 ? normalized : normalized.substring(colon + 1);
    }

    private static String stripPunctuation(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.*-]", "")
                .replaceFirst("^-+", "");
    }

    private static String canonicalTarget(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(colon + 1);
        }
        normalized = stripPunctuation(normalized);
        return normalized.endsWith(".jar") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private static boolean isProtectedTarget(String value) {
        return value.equals("twiopsec") || value.equals("all") || value.equals("*");
    }

    public enum ParseStatus {
        EMPTY,
        VALID,
        INVALID
    }

    public record ParsedCommand(String label, String namespace, List<String> arguments, ParseStatus status) {
        private static final ParsedCommand EMPTY = new ParsedCommand("", "", List.of(), ParseStatus.EMPTY);
        private static final ParsedCommand INVALID = new ParsedCommand("", "", List.of(), ParseStatus.INVALID);
    }
}
