package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.TwiOpSecPlugin;
import com.siberanka.twiopsec.config.SecuritySettings;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.concurrent.atomic.AtomicReference;

public final class SecurityListener implements Listener {
    private static final Component DENIED = Component.text("TwiOpSec blocked this privileged operation.");
    private final TwiOpSecPlugin plugin;
    private final AtomicReference<SecuritySettings> settings;
    private final SecurityEngine engine;
    private final CommandGuard commandGuard;

    public SecurityListener(TwiOpSecPlugin plugin, AtomicReference<SecuritySettings> settings,
                            SecurityEngine engine, CommandGuard commandGuard) {
        this.plugin = plugin;
        this.settings = settings;
        this.engine = engine;
        this.commandGuard = commandGuard;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (settings.get().checkOnJoin()) {
            event.getPlayer().getScheduler().execute(plugin,
                    () -> engine.checkPlayer(event.getPlayer(), CheckTrigger.JOIN), null, 1L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        event.getPlayer().getScheduler().execute(plugin,
                () -> engine.checkPlayer(event.getPlayer(), CheckTrigger.JOIN), null, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (settings.get().checkOnInteract()) {
            engine.checkPlayer(event.getPlayer(), CheckTrigger.INTERACT);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (settings.get().checkOnChat()) {
            event.getPlayer().getScheduler().execute(plugin,
                    () -> engine.checkPlayer(event.getPlayer(), CheckTrigger.CHAT), null, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (settings.get().checkOnCommand() && engine.checkPlayer(event.getPlayer(), CheckTrigger.COMMAND)) {
            event.setCancelled(true);
            return;
        }
        if (commandGuard.inspect(event.getPlayer(), event.getMessage()) != CommandGuard.Decision.ALLOW) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        if (commandGuard.inspect(event.getSender(), event.getCommand()) != CommandGuard.Decision.ALLOW) {
            event.setCancelled(true);
            event.getSender().sendMessage(DENIED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin && !Bukkit.isStopping()) {
            plugin.markUnexpectedDisable("PluginDisableEvent while server was running");
        }
    }
}
