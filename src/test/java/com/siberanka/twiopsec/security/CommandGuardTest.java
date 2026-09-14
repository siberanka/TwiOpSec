package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandGuardTest {
    @TempDir
    Path temporary;

    @Test
    void unloadHardeningRemainsActiveWhenPrivilegeEnforcementIsDisabled() throws IOException {
        Path config = temporary.resolve("config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enforcement.enabled", false);
        yaml.set("hardening.block-runtime-unload-commands", true);
        yaml.save(config.toFile());
        SecuritySettings settings = SettingsLoader.load(config.toFile(), ignored -> {
        });
        CommandGuard guard = new CommandGuard(new AtomicReference<>(settings), null, () -> null);
        ConsoleCommandSender console = ConsoleCommandSender.class.cast(Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null));

        assertEquals(CommandGuard.Decision.BLOCK_RUNTIME_UNLOAD,
                guard.inspect(console, "plugman unload TwiOpSec"));
        assertEquals(CommandGuard.Decision.ALLOW, guard.inspect(console, "stop"));
    }
}
