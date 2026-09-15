package com.siberanka.twiopsec;

import com.siberanka.twiopsec.command.TwiOpSecCommand;
import com.siberanka.twiopsec.config.ImportReport;
import com.siberanka.twiopsec.config.LegacyImporter;
import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.ServerAliasLoader;
import com.siberanka.twiopsec.config.SettingsLoader;
import com.siberanka.twiopsec.security.AuditLogger;
import com.siberanka.twiopsec.security.CommandGuard;
import com.siberanka.twiopsec.security.CitizensNpcPolicy;
import com.siberanka.twiopsec.security.SecurityEngine;
import com.siberanka.twiopsec.security.SecurityListener;
import com.siberanka.twiopsec.security.PermissionRemediator;
import com.siberanka.twiopsec.update.UpdateChecker;
import com.siberanka.twiopsec.update.UpdateNotice;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class TwiOpSecPlugin extends JavaPlugin {
    private final AtomicReference<SecuritySettings> settings = new AtomicReference<>();
    private final AtomicReference<AuditLogger> audit = new AtomicReference<>();
    private final AtomicReference<UpdateChecker.Result> updateStatus =
            new AtomicReference<>(UpdateChecker.Result.disabled("unknown"));
    private final UpdateNotice updateNotice = new UpdateNotice();
    private final AtomicLong updateGeneration = new AtomicLong();
    private final AtomicBoolean unexpectedDisableMarked = new AtomicBoolean();
    private final AtomicBoolean commandsRegistered = new AtomicBoolean();
    private SecurityEngine engine;
    private PermissionRemediator permissionRemediator;
    private LegacyImporter importer;
    private Path dataFolder;
    private volatile String legacySourceFolder = "T2C-OPSecurity";
    private volatile boolean usingLastKnownGood;
    private volatile boolean startupComplete;
    private volatile ScheduledTask updateTask;

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
        CitizensNpcPolicy citizensNpcPolicy = CitizensNpcPolicy.discover(getServer());
        if (securitySettings.citizensServerCommandNpcBypass() && !citizensNpcPolicy.isCitizensAvailable()) {
            getLogger().warning("Citizens NPC compatibility is enabled, but an enabled Citizens plugin was not found; "
                    + "NPC bypass remains inactive.");
        }
        permissionRemediator = new PermissionRemediator(this, settings, audit);
        permissionRemediator.start();
        engine = new SecurityEngine(this, settings, audit, permissionRemediator);
        CommandGuard guard = new CommandGuard(settings, engine, audit::get, serverAliases);
        getServer().getPluginManager().registerEvents(
                new SecurityListener(this, settings, engine, guard, citizensNpcPolicy), this);

        registerCommands();
        engine.reconcileStoredOperators();
        engine.restartPeriodicTask();
        startupComplete = true;
        startUpdateCheck(securitySettings);

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
        updateGeneration.incrementAndGet();
        ScheduledTask currentUpdateTask = updateTask;
        if (currentUpdateTask != null) {
            currentUpdateTask.cancel();
        }
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
        permissionRemediator.start();
        legacySourceFolder = loaded.legacySourceFolder();
        usingLastKnownGood = false;
        engine.restartPeriodicTask();
        refreshPlayerCommandTrees();
        startUpdateCheck(replacementSettings);
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

    public String updateHealth() {
        return updateStatus.get().health();
    }

    public void notifyAvailableUpdate(Player player) {
        UpdateChecker.Result result = updateStatus.get();
        SecuritySettings snapshot = settings.get();
        if (snapshot == null || !updateNotice.claim(player, snapshot, result)) {
            return;
        }
        player.sendMessage(updateNotice.message(result));
    }

    public void clearUpdateNotification(UUID uuid) {
        updateNotice.clear(uuid);
    }

    private synchronized void startUpdateCheck(SecuritySettings snapshot) {
        long generation = updateGeneration.incrementAndGet();
        ScheduledTask current = updateTask;
        if (current != null) {
            current.cancel();
        }
        updateNotice.clearAll();
        String currentVersion = getPluginMeta().getVersion();
        if (!snapshot.updateCheckEnabled()) {
            updateStatus.set(UpdateChecker.Result.disabled(currentVersion));
            updateTask = null;
            return;
        }

        updateStatus.set(UpdateChecker.Result.checking(currentVersion));
        try {
            UpdateChecker checker = new UpdateChecker(currentVersion, snapshot.updateConnectTimeoutSeconds(),
                    snapshot.updateRequestTimeoutSeconds());
            updateTask = getServer().getAsyncScheduler().runNow(this, ignored -> {
                UpdateChecker.Result result = checker.check();
                if (generation != updateGeneration.get() || !isEnabled() || Bukkit.isStopping()) {
                    return;
                }
                updateStatus.set(result);
                announceUpdateResult(result);
            });
        } catch (RuntimeException exception) {
            updateStatus.set(UpdateChecker.Result.unavailable(currentVersion, "scheduler-unavailable"));
            getLogger().warning("Update check could not be scheduled; server security enforcement is unaffected.");
        }
    }

    private void announceUpdateResult(UpdateChecker.Result result) {
        switch (result.status()) {
            case AVAILABLE -> {
                getLogger().warning("TwiOpSec " + result.latestVersion() + " is available from "
                        + result.source().name() + ": " + result.releaseUri());
                try {
                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                        if (!isEnabled() || Bukkit.isStopping()
                                || updateStatus.get() != result) {
                            return;
                        }
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            player.getScheduler().execute(this, () -> notifyAvailableUpdate(player), null, 1L);
                        }
                    });
                } catch (IllegalStateException ignored) {
                    // Shutdown raced the completed metadata request; no notice is needed.
                }
            }
            case UP_TO_DATE -> getLogger().info("TwiOpSec update check completed via "
                    + result.source().name() + "; this build is current.");
            case UNAVAILABLE -> getLogger().warning("TwiOpSec update check was unavailable ("
                    + result.detail() + "); server security enforcement is unaffected.");
            case DISABLED, CHECKING -> {
                // These states are not completion results.
            }
        }
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
