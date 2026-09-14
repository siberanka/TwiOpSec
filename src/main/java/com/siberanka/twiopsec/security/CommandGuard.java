package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class CommandGuard {
    private final AtomicReference<SecuritySettings> settings;
    private final SecurityEngine engine;
    private final AuditLoggerProvider audit;

    public CommandGuard(AtomicReference<SecuritySettings> settings, SecurityEngine engine,
                        AuditLoggerProvider audit) {
        this.settings = settings;
        this.engine = engine;
        this.audit = audit;
    }

    public Decision inspect(CommandSender sender, String rawCommand) {
        SecuritySettings snapshot = settings.get();
        CommandParser.ParsedCommand command = CommandParser.parse(rawCommand);
        if (command.label().isEmpty()) {
            return Decision.ALLOW;
        }
        if (snapshot.blockRuntimeUnloadCommands()
                && CommandParser.attemptsRuntimeUnload(command, snapshot.runtimePluginManagerRoots())) {
            record(sender, "blocked-runtime-unload", command.label());
            return Decision.BLOCK_RUNTIME_UNLOAD;
        }
        if (!snapshot.enabled()) {
            return Decision.ALLOW;
        }
        if (command.label().equals("op")) {
            if (command.arguments().isEmpty() || !engine.mayOpTarget(command.arguments().getFirst())) {
                record(sender, "blocked-op-target", command.arguments().isEmpty() ? "missing" : "untrusted");
                return Decision.BLOCK_OP_TARGET;
            }
            if (sender instanceof Player player && !snapshot.isTrustedOperator(player.getUniqueId())) {
                record(sender, "blocked-op-sender", "untrusted");
                return Decision.BLOCK_OP_SENDER;
            }
        }
        if (snapshot.privilegeCommandRoots().contains(command.label()) && !isConsole(sender)) {
            if (!(sender instanceof Player player) || !snapshot.isTrustedPermissionHolder(player.getUniqueId())) {
                record(sender, "blocked-privilege-command", command.label());
                return Decision.BLOCK_PRIVILEGE_COMMAND;
            }
        }
        return Decision.ALLOW;
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private void record(CommandSender sender, String action, String detail) {
        AuditLogger logger = audit.get();
        if (logger == null) {
            return;
        }
        if (sender instanceof Player player) {
            logger.record(action, player.getName(), player.getUniqueId().toString(), detail);
        } else {
            logger.record(action, sender.getName(), "", detail.toLowerCase(Locale.ROOT));
        }
    }

    @FunctionalInterface
    public interface AuditLoggerProvider {
        AuditLogger get();
    }

    public enum Decision {
        ALLOW,
        BLOCK_RUNTIME_UNLOAD,
        BLOCK_OP_TARGET,
        BLOCK_OP_SENDER,
        BLOCK_PRIVILEGE_COMMAND
    }
}
