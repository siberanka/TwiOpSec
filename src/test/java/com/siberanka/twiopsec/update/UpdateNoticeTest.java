package com.siberanka.twiopsec.update;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNoticeTest {
    private static final UUID TRUSTED = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID UNTRUSTED = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
    private static final URI RELEASE =
            URI.create("https://github.com/siberanka/TwiOpSec/releases/tag/v1.3.0");

    @TempDir
    Path temporary;

    @Test
    void claimsOncePerSessionOnlyForOnlineTrustedOperators() throws IOException {
        SecuritySettings settings = settings();
        UpdateChecker.Result result = available();
        UpdateNotice notice = new UpdateNotice();

        assertTrue(notice.claim(player(TRUSTED, true, true), settings, result));
        assertFalse(notice.claim(player(TRUSTED, true, true), settings, result));
        assertFalse(notice.claim(player(TRUSTED, false, true), settings, result));
        assertFalse(notice.claim(player(TRUSTED, true, false), settings, result));
        assertFalse(notice.claim(player(UNTRUSTED, true, true), settings, result));

        notice.clear(TRUSTED);
        assertTrue(notice.claim(player(TRUSTED, true, true), settings, result));
        notice.clearAll();
        assertTrue(notice.claim(player(TRUSTED, true, true), settings, result));
    }

    @Test
    void buildsClickableMessageFromTheValidatedRelease() {
        Component message = new UpdateNotice().message(available());

        Component link = message.children().getFirst();
        assertNotNull(link.clickEvent());
        ClickEvent.Payload.Text payload = assertInstanceOf(ClickEvent.Payload.Text.class,
                link.clickEvent().payload());
        assertEquals(RELEASE.toASCIIString(), payload.value());
    }

    private SecuritySettings settings() throws IOException {
        Path config = temporary.resolve("config.yml");
        Files.writeString(config, """
                schema-version: 1
                trusted:
                  operators:
                    owner:
                      name: Owner_1
                      uuid: 12345678-1234-1234-1234-1234567890ab
                """);
        return SettingsLoader.load(config.toFile(), ignored -> {
        });
    }

    private static UpdateChecker.Result available() {
        return new UpdateChecker.Result(UpdateChecker.Status.AVAILABLE, "1.2.0", "1.3.0",
                RELEASE, UpdateChecker.Source.GITHUB, "ok");
    }

    private static Player player(UUID uuid, boolean online, boolean operator) {
        return Player.class.cast(Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnline" -> online;
                    case "isOp" -> operator;
                    default -> defaultValue(method);
                }));
    }

    private static Object defaultValue(Method method) {
        if (!method.getReturnType().isPrimitive()) {
            return null;
        }
        if (method.getReturnType() == boolean.class) {
            return false;
        }
        if (method.getReturnType() == char.class) {
            return '\0';
        }
        return 0;
    }
}
