package com.siberanka.twiopsec.command;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.ImportReport;
import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.security.CheckTrigger;
import com.siberanka.twiopsec.security.SecurityEngine;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class TwiOpSecCommand implements BasicCommand {
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
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!authorized(sender)) {
            sender.sendMessage(Component.translatable("command.unknown.command").color(NamedTextColor.RED));
            return;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "reload" -> {
                if (plugin.reloadSecuritySettings()) {
                    sender.sendMessage(Component.text("TwiOpSec configuration reloaded."));
                } else {
                    sender.sendMessage(Component.text("TwiOpSec rejected the configuration; active settings are unchanged.")
                            .color(NamedTextColor.RED));
                }
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
    }

    private boolean authorized(CommandSender sender) {
        return sender instanceof ConsoleCommandSender;
    }

    private void status(CommandSender sender) {
        SecuritySettings snapshot = settings.get();
        sender.sendMessage(Component.text("TwiOpSec " + plugin.getPluginMeta().getVersion()
                + ": enabled=" + snapshot.enabled() + ", trusted-operators="
                + snapshot.trustedOperators().size() + ", trusted-permission-holders="
                + snapshot.trustedPermissionHolders().size() + ", protected-permissions="
                + snapshot.protectedPermissions().size() + ", config-source="
                + (plugin.usingLastKnownGood() ? "last-known-good" : "primary")
                + ", audit=" + plugin.auditHealth() + "."));
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
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!visibleTo(sender) || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
        return visibleTo(sender);
    }

    private boolean visibleTo(CommandSender sender) {
        if (authorized(sender)) {
            return true;
        }
        return sender instanceof Player player && player.isOp()
                && settings.get().isTrustedOperator(player.getUniqueId());
    }
}
