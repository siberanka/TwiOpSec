package com.siberanka.twiopsec.command;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.ImportReport;
import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.security.CheckTrigger;
import com.siberanka.twiopsec.security.SecurityEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class TwiOpSecCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "import", "check");
    private final TwiOpSecPlugin plugin;
    private final AtomicReference<SecuritySettings> settings;
    private final SecurityEngine engine;

    public TwiOpSecCommand(TwiOpSecPlugin plugin, AtomicReference<SecuritySettings> settings,
                           SecurityEngine engine) {
        this.plugin = plugin;
        this.settings = settings;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!authorized(sender)) {
            sender.sendMessage(Component.text("TwiOpSec: trusted administrator identity required."));
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "reload" -> {
                plugin.reloadSecuritySettings();
                sender.sendMessage(Component.text("TwiOpSec configuration reloaded."));
            }
            case "import" -> {
                ImportReport report = plugin.importLegacy(true);
                sender.sendMessage(Component.text("TwiOpSec import: " + report.status().name().toLowerCase(Locale.ROOT)
                        + " (operators=" + report.operators() + ", permission-holders="
                        + report.permissionHolders() + ", permissions=" + report.permissions() + ")."));
            }
            case "check" -> runCheck(sender);
            default -> sender.sendMessage(Component.text("Usage: /twiopsec <status|reload|import|check>"));
        }
        return true;
    }

    private boolean authorized(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return true;
        }
        if (!(sender instanceof Player player) || !sender.hasPermission("twiopsec.admin")) {
            return false;
        }
        SecuritySettings snapshot = settings.get();
        return snapshot.isTrustedOperator(player.getUniqueId())
                || snapshot.isTrustedPermissionHolder(player.getUniqueId());
    }

    private void status(CommandSender sender) {
        SecuritySettings snapshot = settings.get();
        sender.sendMessage(Component.text("TwiOpSec " + plugin.getPluginMeta().getVersion()
                + ": enabled=" + snapshot.enabled() + ", trusted-operators="
                + snapshot.trustedOperators().size() + ", trusted-permission-holders="
                + snapshot.trustedPermissionHolders().size() + ", protected-permissions="
                + snapshot.protectedPermissions().size() + "."));
    }

    private void runCheck(CommandSender sender) {
        if (sender instanceof Player player) {
            boolean violation = engine.checkPlayer(player, CheckTrigger.MANUAL);
            if (!violation && player.isOnline()) {
                player.sendMessage(Component.text("TwiOpSec: no violation detected."));
            }
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> engine.checkPlayer(player, CheckTrigger.MANUAL), null, 1L);
        }
        sender.sendMessage(Component.text("TwiOpSec check scheduled for all online players."));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!authorized(sender) || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
