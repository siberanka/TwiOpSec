package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
class CitizensNpcPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void bypassRequiresOptInEnabledCitizensAndCitizensOwnedTrueMetadata() throws IOException {
        Plugin citizens = plugin(true);
        Plugin impostor = plugin(true);
        CitizensNpcPolicy policy = CitizensNpcPolicy.forTesting(citizens);
        SecuritySettings enabled = settings(true);

        assertTrue(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/server survival", enabled));
        assertTrue(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/bungeecord:server lobby", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/op Intruder", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/execute as @a run server lobby", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/server", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/server lobby /op Intruder", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/server lobby extra", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(impostor, true)), "/server survival", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, false)), "/server survival", enabled));
        assertFalse(policy.mayRunServerCommand(player(), "/server survival", enabled));
        assertFalse(CitizensNpcPolicy.forTesting(plugin(false))
                .mayRunServerCommand(player(metadata(citizens, true)), "/server survival", enabled));
        assertFalse(policy.mayRunServerCommand(
                player(metadata(citizens, true)), "/server survival", settings(false)));
    }

    @Test
    void brokenMetadataFailsClosed() throws IOException {
        Plugin citizens = plugin(true);
        MetadataValue broken = proxy(MetadataValue.class, (proxy, method, args) -> {
            if (method.getName().equals("getOwningPlugin")) {
                return citizens;
            }
            if (method.getName().equals("asBoolean")) {
                throw new IllegalStateException("broken metadata");
            }
            return defaultValue(method);
        });

        assertFalse(CitizensNpcPolicy.forTesting(citizens)
                .mayRunServerCommand(player(broken), "/server survival", settings(true)));
    }

    private SecuritySettings settings(boolean enabled) throws IOException {
        Path config = temporary.resolve("settings-" + enabled + ".yml");
        Files.writeString(config, """
                schema-version: 1
                compatibility:
                  citizens:
                    server-command-npc-bypass: %s
                """.formatted(enabled));
        return SettingsLoader.load(config.toFile(), ignored -> {
        });
    }

    private static Plugin plugin(boolean enabled) {
        return proxy(Plugin.class, (proxy, method, args) ->
                method.getName().equals("isEnabled") ? enabled : defaultValue(method));
    }

    private static MetadataValue metadata(Plugin owner, boolean value) {
        return proxy(MetadataValue.class, (proxy, method, args) -> switch (method.getName()) {
            case "getOwningPlugin" -> owner;
            case "asBoolean" -> value;
            default -> defaultValue(method);
        });
    }

    private static Player player(MetadataValue... values) {
        return proxy(Player.class, (proxy, method, args) ->
                method.getName().equals("getMetadata") ? List.of(values) : defaultValue(method));
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return 0;
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
