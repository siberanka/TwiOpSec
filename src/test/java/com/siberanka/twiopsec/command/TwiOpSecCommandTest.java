package com.siberanka.twiopsec.command;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwiOpSecCommandTest {
    private static final UUID TRUSTED = UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    @TempDir
    Path temporary;

    @Test
    void commandTreeIsVisibleOnlyToLocalConsoleAndActiveWhitelistedOperators() throws IOException {
        TwiOpSecCommand command = command();

        assertTrue(command.canUse(proxy(ConsoleCommandSender.class, (proxy, method, args) -> defaultValue(method))));
        assertFalse(command.canUse(proxy(RemoteConsoleCommandSender.class,
                (proxy, method, args) -> defaultValue(method))));
        assertTrue(command.canUse(player(TRUSTED, true, new ArrayList<>())));
        assertFalse(command.canUse(player(TRUSTED, false, new ArrayList<>())));
        assertFalse(command.canUse(player(UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab"),
                true, new ArrayList<>())));
    }

    @Test
    void playerExecutionReceivesVanillaUnknownCommandTranslation() throws IOException {
        List<Component> messages = new ArrayList<>();
        Player player = player(TRUSTED, true, messages);
        CommandSourceStack source = proxy(CommandSourceStack.class, (proxy, method, args) ->
                method.getName().equals("getSender") ? player : defaultValue(method));

        command().execute(source, new String[]{"status"});

        TranslatableComponent message = assertInstanceOf(TranslatableComponent.class, messages.getFirst());
        assertEquals("command.unknown.command", message.key());
    }

    private TwiOpSecCommand command() throws IOException {
        Path config = temporary.resolve("config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("trusted.operators.owner.name", "Owner_1");
        yaml.set("trusted.operators.owner.uuid", TRUSTED.toString());
        yaml.save(config.toFile());
        SecuritySettings settings = SettingsLoader.load(config.toFile(), ignored -> {
        });
        return new TwiOpSecCommand(null, new AtomicReference<>(settings), null);
    }

    private static Player player(UUID uuid, boolean operator, List<Component> messages) {
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "isOp" -> operator;
            case "sendMessage" -> {
                if (args != null && args.length == 1 && args[0] instanceof Component component) {
                    messages.add(component);
                }
                yield null;
            }
            default -> defaultValue(method);
        });
    }

    private static Object defaultValue(Method method) {
        if (method.getName().equals("toString")) {
            return "test-proxy";
        }
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
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
        throw new IllegalStateException("Unsupported primitive return type: " + type);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
