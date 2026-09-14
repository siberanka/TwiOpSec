package com.siberanka.twiopsec;

import com.siberanka.twiopsec.command.TwiOpSecCommand;
import com.siberanka.twiopsec.config.ImportReport;
import com.siberanka.twiopsec.config.LegacyImporter;
import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import com.siberanka.twiopsec.security.AuditLogger;
import com.siberanka.twiopsec.security.CommandGuard;
import com.siberanka.twiopsec.security.SecurityEngine;
import com.siberanka.twiopsec.security.SecurityListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class TwiOpSecPlugin extends JavaPlugin {
    private final AtomicReference<SecuritySettings> settings = new AtomicReference<>();
    private final AtomicReference<AuditLogger> audit = new AtomicReference<>();
    private final AtomicBoolean unexpectedDisableMarked = new AtomicBoolean();
    private final AtomicBoolean commandsRegistered = new AtomicBoolean();
    private SecurityEngine engine;
    private LegacyImporter importer;
    private volatile boolean startupComplete;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsFolder = Objects.requireNonNull(dataFolder.getParent(), "plugins folder");
        importer = new LegacyImporter(dataFolder, pluginsFolder, message -> getLogger().warning(message));

        warnAboutUnexpectedDisable(dataFolder.resolve("unexpected-disable.marker"));
        if (getConfig().getBoolean("legacy-import.automatic", true)) {
            String source = getConfig().getString("legacy-import.source-folder", "T2C-OPSecurity");
            ImportReport report = importer.importLegacy(source, false);
            if (report.status() == ImportReport.Status.FAILED) {
                getLogger().severe("Legacy data was detected but could not be imported; TwiOpSec will not start.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (report.status() == ImportReport.Status.IMPORTED) {
                getLogger().info("Imported T2C data: " + report.operators() + " operator identities, "
                        + report.permissionHolders() + " permission identities, " + report.permissions()
                        + " protected permission entries.");
            }
        }

        reloadConfig();
        SecuritySettings loaded = SettingsLoader.load(getDataFolder().toPath().resolve("config.yml").toFile(),
                message -> getLogger().warning(message));
        settings.set(loaded);
        if (loaded.auditLog()) {
            audit.set(new AuditLogger(dataFolder, loaded.auditQueueCapacity(), getLogger()));
        }
        engine = new SecurityEngine(this, settings, audit);
        CommandGuard guard = new CommandGuard(settings, engine, audit::get);
        getServer().getPluginManager().registerEvents(new SecurityListener(this, settings, engine, guard), this);

        registerCommands();
        engine.restartPeriodicTask();
        startupComplete = true;

        getLogger().info("TwiOpSec enabled with " + loaded.trustedOperators().size()
                + " trusted operators and " + loaded.protectedPermissions().size() + " protected permissions.");
    }

    private void registerCommands() {
        if (!commandsRegistered.compareAndSet(false, true)) {
            return;
        }
        TwiOpSecCommand command = new TwiOpSecCommand(this, settings, engine);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("twiopsec", "Inspect and administer TwiOpSec from the local console.",
                        java.util.List.of("twios", "opsec"), command));
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stop();
        }
        if (startupComplete && !Bukkit.isStopping()) {
            markUnexpectedDisable("onDisable invoked while server was running");
        }
        AuditLogger old = audit.getAndSet(null);
        if (old != null) {
            old.record("plugin-disable", "server", "", Bukkit.isStopping() ? "server-stop" : "runtime-disable");
            old.close();
        }
    }

    public synchronized void reloadSecuritySettings() {
        reloadConfig();
        SecuritySettings loaded = SettingsLoader.load(getDataFolder().toPath().resolve("config.yml").toFile(),
                message -> getLogger().warning(message));
        AuditLogger replacement = loaded.auditLog()
                ? new AuditLogger(getDataFolder().toPath(), loaded.auditQueueCapacity(), getLogger()) : null;
        settings.set(loaded);
        AuditLogger old = audit.getAndSet(replacement);
        if (old != null) {
            old.close();
        }
        engine.restartPeriodicTask();
        refreshPlayerCommandTrees();
    }

    private void refreshPlayerCommandTrees() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(this, player::updateCommands, null, 1L);
        }
    }

    public synchronized ImportReport importLegacy(boolean force) {
        String source = getConfig().getString("legacy-import.source-folder", "T2C-OPSecurity");
        ImportReport report = importer.importLegacy(source, force);
        if (report.status() == ImportReport.Status.IMPORTED) {
            reloadSecuritySettings();
        }
        return report;
    }

    public void markUnexpectedDisable(String reason) {
        if (!startupComplete || !unexpectedDisableMarked.compareAndSet(false, true)) {
            return;
        }
        Path marker = getDataFolder().toPath().resolve("unexpected-disable.marker");
        try {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            String line = Instant.now() + " " + reason.replace('\r', ' ').replace('\n', ' ') + System.lineSeparator();
            Files.writeString(marker, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not persist unexpected-disable evidence", exception);
        }
    }

    private void warnAboutUnexpectedDisable(Path marker) {
        if (Files.isRegularFile(marker)) {
            getLogger().severe("An earlier runtime disable of TwiOpSec was detected. Investigate plugins and logs; "
                    + "same-process code cannot be treated as a security boundary.");
        }
    }
}
