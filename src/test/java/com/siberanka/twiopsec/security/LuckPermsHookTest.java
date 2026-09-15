package com.siberanka.twiopsec.security;

import com.siberanka.twiopsec.config.SecuritySettings;
import com.siberanka.twiopsec.config.SettingsLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsHookTest {
    private static final UUID USER_ID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    @TempDir
    Path temporary;

    @Test
    void transientEventRemovesOnlyTheTransientMapWithoutDurableSave() throws IOException {
        Fixture fixture = new Fixture(settings(false));
        Node protectedNode = node("minecraft.command.op", true);
        fixture.user.nodes.get(DataType.NORMAL).add(protectedNode);
        fixture.user.nodes.get(DataType.TRANSIENT).add(protectedNode);

        fixture.hook.onNodeAdd(event(fixture.user.target, protectedNode, DataType.TRANSIENT));

        assertTrue(fixture.user.nodes.get(DataType.TRANSIENT).isEmpty());
        assertEquals(List.of(protectedNode), fixture.user.nodes.get(DataType.NORMAL));
        assertEquals(0, fixture.userSaves.get());
    }

    @Test
    void enduringUserAndGroupEventsAreRemovedAndSavedButFalseAndHarmlessNodesRemain() throws IOException {
        Fixture fixture = new Fixture(settings(false));
        Node protectedNode = node("luckperms.*", true);
        Node falseNode = node("minecraft.command.op", false);
        Node harmless = node("example.harmless", true);
        fixture.user.nodes.get(DataType.NORMAL).addAll(List.of(protectedNode, falseNode, harmless));
        fixture.group.nodes.get(DataType.NORMAL).add(protectedNode);

        fixture.hook.onNodeAdd(event(fixture.user.target, protectedNode, DataType.NORMAL));
        fixture.hook.onNodeAdd(event(fixture.user.target, falseNode, DataType.NORMAL));
        fixture.hook.onNodeAdd(event(fixture.user.target, harmless, DataType.NORMAL));
        fixture.hook.onNodeAdd(event(fixture.group.target, protectedNode, DataType.NORMAL));

        assertEquals(List.of(falseNode, harmless), fixture.user.nodes.get(DataType.NORMAL));
        assertTrue(fixture.group.nodes.get(DataType.NORMAL).isEmpty());
        assertEquals(1, fixture.userSaves.get());
        assertEquals(1, fixture.groupSaves.get());
    }

    @Test
    void trustedUserKeepsBothDataTypesButGroupsAreNeverIdentityTrusted() throws IOException {
        Fixture fixture = new Fixture(settings(true));
        Node protectedNode = node("essentials.*", true);
        for (DataType type : DataType.values()) {
            fixture.user.nodes.get(type).add(protectedNode);
            fixture.hook.onNodeAdd(event(fixture.user.target, protectedNode, type));
        }
        fixture.group.nodes.get(DataType.NORMAL).add(protectedNode);

        fixture.hook.reconcileLoadedData();

        for (DataType type : DataType.values()) {
            assertEquals(List.of(protectedNode), fixture.user.nodes.get(type));
        }
        assertTrue(fixture.group.nodes.get(DataType.NORMAL).isEmpty());
        assertFalse(fixture.hook.removeDirectProtectedNodes(USER_ID));
        assertEquals(0, fixture.userSaves.get());
        assertEquals(1, fixture.groupSaves.get());
    }

    @Test
    void loadedReconciliationRemovesBothMapsAndSavesEachEnduringHolderOnce() throws IOException {
        Fixture fixture = new Fixture(settings(false));
        Node protectedNode = node("paper.command.reload", true);
        Node harmless = node("example.harmless", true);
        for (DataType type : DataType.values()) {
            fixture.user.nodes.get(type).addAll(List.of(protectedNode, harmless));
            fixture.group.nodes.get(type).addAll(List.of(protectedNode, harmless));
        }

        fixture.hook.reconcileLoadedData();

        for (DataType type : DataType.values()) {
            assertEquals(List.of(harmless), fixture.user.nodes.get(type));
            assertEquals(List.of(harmless), fixture.group.nodes.get(type));
        }
        assertEquals(1, fixture.userSaves.get());
        assertEquals(1, fixture.groupSaves.get());
    }

    @Test
    void asyncAndSynchronousStorageFailuresAreReportedWithoutRestoringPrivileges() throws IOException {
        Fixture fixture = new Fixture(settings(false));
        fixture.saveOutcome = CompletableFuture.failedFuture(new IllegalStateException("private storage detail"));
        Node protectedNode = node("minecraft.command.op", true);
        fixture.user.nodes.get(DataType.NORMAL).add(protectedNode);
        fixture.hook.onNodeAdd(event(fixture.user.target, protectedNode, DataType.NORMAL));
        fixture.throwOnSave = true;
        fixture.group.nodes.get(DataType.NORMAL).add(protectedNode);
        fixture.hook.onNodeAdd(event(fixture.group.target, protectedNode, DataType.NORMAL));

        assertTrue(fixture.user.nodes.get(DataType.NORMAL).isEmpty());
        assertTrue(fixture.group.nodes.get(DataType.NORMAL).isEmpty());
        assertEquals(2, fixture.logMessages.size());
        assertTrue(fixture.logMessages.stream().allMatch(message -> message.contains("could not be persisted")));
        assertFalse(fixture.logMessages.toString().contains("private storage detail"));
    }

    @Test
    void reloadSnapshotCanDisableAndReenableTheNativeHook() throws IOException {
        Fixture fixture = new Fixture(settings(false));
        Node protectedNode = node("minecraft.command.op", true);
        fixture.user.nodes.get(DataType.NORMAL).add(protectedNode);
        Path disabledConfig = temporary.resolve("disabled-native.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("permission-remediation.luckperms-native-hook", false);
        yaml.save(disabledConfig.toFile());
        fixture.activeSettings.set(SettingsLoader.load(disabledConfig.toFile(), ignored -> { }));

        fixture.hook.onNodeAdd(event(fixture.user.target, protectedNode, DataType.NORMAL));
        fixture.hook.reconcileLoadedData();
        assertEquals(List.of(protectedNode), fixture.user.nodes.get(DataType.NORMAL));
        assertEquals(0, fixture.userSaves.get());

        fixture.activeSettings.set(settings(false));
        fixture.hook.reconcileLoadedData();
        assertTrue(fixture.user.nodes.get(DataType.NORMAL).isEmpty());
        assertEquals(1, fixture.userSaves.get());
    }

    private SecuritySettings settings(boolean trusted) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        if (trusted) {
            yaml.set("trusted.operators.owner.name", "TestOwner");
            yaml.set("trusted.operators.owner.uuid", USER_ID.toString());
        }
        Path config = temporary.resolve("settings-" + trusted + ".yml");
        yaml.save(config.toFile());
        return SettingsLoader.load(config.toFile(), ignored -> { });
    }

    private static Node node(String key, boolean value) {
        return proxy(Node.class, (instance, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            case "getValue" -> value;
            case "getType" -> NodeType.PERMISSION;
            default -> defaultValue(instance, method, args);
        });
    }

    private static NodeAddEvent event(PermissionHolder target, Node node, DataType type) {
        return proxy(NodeAddEvent.class, (instance, method, args) -> switch (method.getName()) {
            case "getTarget" -> target;
            case "getNode" -> node;
            case "getDataType" -> type;
            default -> defaultValue(instance, method, args);
        });
    }

    private static final class HolderState<T extends PermissionHolder> {
        private final Map<DataType, List<Node>> nodes = new EnumMap<>(DataType.class);
        private final Map<DataType, NodeMap> maps = new EnumMap<>(DataType.class);
        private final T target;

        private HolderState(Class<T> type) {
            for (DataType dataType : DataType.values()) {
                List<Node> data = new ArrayList<>();
                nodes.put(dataType, data);
                maps.put(dataType, proxy(NodeMap.class, (instance, method, args) -> switch (method.getName()) {
                    case "toCollection" -> List.copyOf(data);
                    case "remove" -> data.remove(args[0]) ? DataMutateResult.SUCCESS : DataMutateResult.FAIL_LACKS;
                    default -> defaultValue(instance, method, args);
                }));
            }
            target = proxy(type, (instance, method, args) -> switch (method.getName()) {
                case "getData" -> maps.get(args[0]);
                case "data" -> maps.get(DataType.NORMAL);
                case "transientData" -> maps.get(DataType.TRANSIENT);
                case "getUniqueId" -> USER_ID;
                case "getUsername" -> "TestOwner";
                case "getName" -> "testgroup";
                default -> defaultValue(instance, method, args);
            });
        }
    }

    private static final class Fixture {
        private final HolderState<User> user = new HolderState<>(User.class);
        private final HolderState<Group> group = new HolderState<>(Group.class);
        private final AtomicInteger userSaves = new AtomicInteger();
        private final AtomicInteger groupSaves = new AtomicInteger();
        private final List<String> logMessages = new ArrayList<>();
        private CompletableFuture<Void> saveOutcome = CompletableFuture.completedFuture(null);
        private boolean throwOnSave;
        private final LuckPermsHook hook;
        private final AtomicReference<SecuritySettings> activeSettings;

        private Fixture(SecuritySettings settings) {
            activeSettings = new AtomicReference<>(settings);
            UserManager users = proxy(UserManager.class, (instance, method, args) -> switch (method.getName()) {
                case "getUser" -> user.target;
                case "getLoadedUsers" -> Set.of(user.target);
                case "saveUser" -> save(userSaves);
                default -> defaultValue(instance, method, args);
            });
            GroupManager groups = proxy(GroupManager.class, (instance, method, args) -> switch (method.getName()) {
                case "getLoadedGroups" -> Set.of(group.target);
                case "saveGroup" -> save(groupSaves);
                default -> defaultValue(instance, method, args);
            });
            LuckPerms api = proxy(LuckPerms.class, (instance, method, args) -> switch (method.getName()) {
                case "getUserManager" -> users;
                case "getGroupManager" -> groups;
                default -> defaultValue(instance, method, args);
            });
            Logger logger = Logger.getLogger("LuckPermsHookTest." + UUID.randomUUID());
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logMessages.add(record.getMessage()); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            hook = LuckPermsHook.forTesting(logger, api, activeSettings, new AtomicReference<>());
        }

        private CompletableFuture<Void> save(AtomicInteger count) {
            count.incrementAndGet();
            if (throwOnSave) {
                throw new IllegalStateException("private storage detail");
            }
            return saveOutcome;
        }
    }

    private static Object defaultValue(Object instance, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "test-proxy";
            default -> method.getReturnType() == boolean.class ? false
                    : method.getReturnType() == int.class ? 0 : null;
        };
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
