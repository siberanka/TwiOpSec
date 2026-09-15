package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRemediatorTest {
    @TempDir
    Path temporary;

    @Test
    void duplicateProtectedGrantsOnDifferentAttachmentsAreAllRemoved() throws IOException {
        AtomicReference<Set<PermissionAttachmentInfo>> effective = new AtomicReference<>(Set.of());
        Player player = player(effective);
        PermissionAttachment first = new PermissionAttachment(plugin(), player);
        PermissionAttachment second = new PermissionAttachment(plugin(), player);
        first.setPermission("minecraft.command.op", true);
        second.setPermission("minecraft.command.op", true);
        second.setPermission("example.harmless", true);
        second.setPermission("luckperms.*", false);
        effective.set(Set.of(
                new PermissionAttachmentInfo(player, "minecraft.command.op", first, true),
                new PermissionAttachmentInfo(player, "minecraft.command.op", second, true),
                new PermissionAttachmentInfo(player, "example.harmless", second, true),
                new PermissionAttachmentInfo(player, "luckperms.*", second, false)));

        assertTrue(PermissionRemediator.removeAttachments(player, settings()));

        assertFalse(first.getPermissions().containsKey("minecraft.command.op"));
        assertFalse(second.getPermissions().containsKey("minecraft.command.op"));
        assertTrue(second.getPermissions().containsKey("example.harmless"));
        assertTrue(second.getPermissions().containsKey("luckperms.*"));
    }

    @Test
    void attachmentlessAndNegativeNodesAreNotBlindlyMutated() throws IOException {
        AtomicReference<Set<PermissionAttachmentInfo>> effective = new AtomicReference<>(Set.of());
        Player player = player(effective);
        PermissionAttachment negative = new PermissionAttachment(plugin(), player);
        negative.setPermission("minecraft.command.op", false);
        effective.set(Set.of(
                new PermissionAttachmentInfo(player, "minecraft.command.op", negative, false),
                new PermissionAttachmentInfo(player, "essentials.*", null, true)));

        assertFalse(PermissionRemediator.removeAttachments(player, settings()));
        assertTrue(negative.getPermissions().containsKey("minecraft.command.op"));
    }

    private SecuritySettings settings() throws IOException {
        Path config = temporary.resolve("config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.save(config.toFile());
        return SettingsLoader.load(config.toFile(), ignored -> { });
    }

    private static Player player(AtomicReference<Set<PermissionAttachmentInfo>> effective) {
        return proxy(Player.class, (instance, method, args) -> method.getName().equals("getEffectivePermissions")
                ? effective.get() : method.getReturnType() == boolean.class ? false : null);
    }

    private static Plugin plugin() {
        return proxy(Plugin.class, (instance, method, args) -> method.getName().equals("isEnabled")
                ? true : method.getName().equals("getName") ? "test-provider" : null);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
