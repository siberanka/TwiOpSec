package com.siberanka.twiopsec;

import com.siberanka.twiopsec.command.TwiOpSecCommand;
import com.siberanka.twiopsec.config.ImportReport;
import com.siberanka.twiopsec.config.LegacyImporter;
import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.ServerAliasLoader;
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
import java.util.Map;
import java.util.List;
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
    private Path dataFolder;
    private volatile String legacySourceFolder = "T2C-OPSecurity";
    private volatile boolean usingLastKnownGood;
    private volatile boolean startupComplete;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFolder = getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsFolder = Objects.requireNonNull(dataFolder.getParent(), "plugins folder");
        importer = new LegacyImporter(dataFolder, pluginsFolder, this::warn);

        warnAboutUnexpectedDisable(dataFolder.resolve("unexpected-disable.marker"));
        SettingsLoader.LoadedConfiguration loaded;
        Map<String, List<String>> serverAliases;
        try {
            loaded = loadStartupConfiguration();
            legacySourceFolder = loaded.legacySourceFolder();
            if (!usingLastKnownGood && loaded.automaticLegacyImport()) {
                ImportReport report = importer.importLegacy(legacySourceFolder, false);
                if (report.status() == ImportReport.Status.FAILED) {
                    failStartup("Legacy data was detected but could not be imported: " + report.detail());
                    return;
                }
                if (report.status() == ImportReport.Status.IMPORTED) {
                    getLogger().info("Imported T2C data: " + report.operators() + " operator identities, "
                            + report.permissionHolders() + " permission identities, " + report.permissions()
                            + " protected permission entries.");
                    loaded = SettingsLoader.loadAll(dataFolder.resolve("config.yml"), this::warn);
                    legacySourceFolder = loaded.legacySourceFolder();
                }
            }
            if (!usingLastKnownGood) {
                SettingsLoader.persistLastKnownGood(dataFolder.resolve("config.last-known-good.yml"), loaded);
            }
            Path serverRoot = Objects.requireNonNull(pluginsFolder.getParent(), "server root");
            serverAliases = ServerAliasLoader.load(serverRoot);
        } catch (IOException | RuntimeException exception) {
            failStartup("No valid TwiOpSec configuration is available: " + safeException(exception));
            return;
        }

        SecuritySettings securitySettings = loaded.settings();
        settings.set(securitySettings);
        if (securitySettings.auditLog()) {
            audit.set(new AuditLogger(dataFolder, securitySettings.auditQueueCapacity(), getLogger()));
        }
        engine = new SecurityEngine(this, settings, audit);
        CommandGuard guard = new CommandGuard(settings, engine, audit::get, serverAliases);
        getServer().getPluginManager().registerEvents(new SecurityListener(this, settings, engine, guard), this);

        registerCommands();
        engine.reconcileStoredOperators();
        engine.restartPeriodicTask();
        startupComplete = true;

        getLogger().info("TwiOpSec enabled with " + securitySettings.trustedOperators().size()
                + " trusted operators and " + securitySettings.protectedPermissions().size()
                + " protected permissions" + (usingLastKnownGood ? " using last-known-good recovery" : "") + '.');
    }

    private SettingsLoader.LoadedConfiguration loadStartupConfiguration() throws IOException {
        Path primary = dataFolder.resolve("config.yml");
        try {
            usingLastKnownGood = false;
            return SettingsLoader.loadAll(primary, this::warn);
        } catch (IOException primaryFailure) {
            Path fallback = dataFolder.resolve("config.last-known-good.yml");
            getLogger().log(Level.SEVERE, "Primary TwiOpSec config was rejected; attempting last-known-good recovery",
                    primaryFailure);
            try {
                SettingsLoader.LoadedConfiguration recovered = SettingsLoader.loadAll(fallback, this::warn);
                usingLastKnownGood = true;
                getLogger().severe("TwiOpSec is running from config.last-known-good.yml; repair config.yml before reload.");
                return recovered;
            } catch (IOException fallbackFailure) {
                primaryFailure.addSuppressed(fallbackFailure);
                throw primaryFailure;
            }
        }
    }

    private void failStartup(String reason) {
        getLogger().severe(reason);
        Path marker = dataFolder.resolve("startup-failure.marker");
        try {
            String line = Instant.now() + " " + reason.replace('\r', ' ').replace('\n', ' ')
                    + System.lineSeparator();
            Files.writeString(marker, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException markerFailure) {
            getLogger().log(Level.SEVERE, "Could not persist startup failure evidence", markerFailure);
        }
        getLogger().severe("Fail-closed policy is stopping the server instead of leaving it online without TwiOpSec.");
        getServer().shutdown();
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

    public synchronized boolean reloadSecuritySettings() {
        SettingsLoader.LoadedConfiguration loaded;
        try {
            loaded = SettingsLoader.loadAll(dataFolder.resolve("config.yml"), this::warn);
            SettingsLoader.persistLastKnownGood(dataFolder.resolve("config.last-known-good.yml"), loaded);
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Rejected TwiOpSec reload; the active settings were left unchanged", exception);
            AuditLogger current = audit.get();
            if (current != null) {
                current.record("config-reload-rejected", "server", "", safeException(exception));
            }
            return false;
        }

        SecuritySettings replacementSettings = loaded.settings();
        AuditLogger replacement = replacementSettings.auditLog()
                ? new AuditLogger(dataFolder, replacementSettings.auditQueueCapacity(), getLogger()) : null;
        settings.set(replacementSettings);
        AuditLogger old = audit.getAndSet(replacement);
        if (old != null) {
            old.close();
        }
        legacySourceFolder = loaded.legacySourceFolder();
        usingLastKnownGood = false;
        engine.restartPeriodicTask();
        refreshPlayerCommandTrees();
        return true;
    }

    private void refreshPlayerCommandTrees() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(this, player::updateCommands, null, 1L);
        }
    }

    public synchronized ImportReport importLegacy(boolean force) {
        ImportReport report = importer.importLegacy(legacySourceFolder, force);
        if (report.status() == ImportReport.Status.IMPORTED && !reloadSecuritySettings()) {
            getLogger().severe("Legacy import committed but the generated configuration could not be activated.");
        }
        return report;
    }

    public boolean usingLastKnownGood() {
        return usingLastKnownGood;
    }

    public String auditHealth() {
        AuditLogger logger = audit.get();
        if (logger == null) {
            return "disabled";
        }
        return logger.isHealthy() ? "healthy,dropped=" + logger.droppedEvents()
                : "degraded,dropped=" + logger.droppedEvents() + ",error=" + logger.lastFailure();
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

    private void warn(String message) {
        getLogger().warning(message);
    }

    private static String safeException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String clean = message.replace('\r', ' ').replace('\n', ' ');
        return clean.substring(0, Math.min(clean.length(), 240));
    }
}
