# Security model

## Assets and trust decisions

TwiOpSec protects operator state, configured high-impact permission nodes, and commands capable of granting those privileges. Authorization uses UUIDs; names are retained only for administration and logs. Operator trust and protected-permission trust are separate sets.

The local server console is the only principal allowed to execute TwiOpSec's own commands. RCON, command blocks, players and unknown sender implementations cannot execute them. In-game command-tree visibility is limited to players who are both currently OP and present by UUID in `trusted.operators`; an execution attempt still receives the server's translatable unknown-command response. Console and RCON remain trusted for other configured privilege-management roots such as LuckPerms, while command blocks and unknown senders do not.

## Enforced invariants

1. Import never removes the built-in protected permissions.
2. A malformed or incomplete detected legacy source cannot partially commit a new configuration.
3. Placeholder or malformed UUIDs never become trusted.
4. A non-whitelisted OP is deopped before optional follow-up commands and kick.
5. Entity mutation runs through that player's Folia entity scheduler; cross-player enumeration uses the global scheduler.
6. Audit input is bounded and control/newline characters are encoded or removed.
7. Server `stop` remains available and does not create unexpected-disable evidence.
8. Bukkit `commands.yml` aliases cannot hide protected command roots; malformed alias data fails startup closed.
9. Persistent operator entries are reconciled against the UUID allowlist on startup using the global scheduler.

## Defensive controls

- No regex is used for untrusted permission patterns. Only exact nodes and a final `.*` namespace wildcard are accepted.
- Imported files must be regular files below the selected plugins folder and at most 2 MiB.
- Import creates backup copies, hashes inputs, atomically replaces the target configuration, and writes its commit marker last.
- Enforcement command templates are length/count bounded and reject embedded newlines.
- Settings are immutable snapshots exchanged atomically; reload does not mutate a live configuration object.
- Server aliases are read through the same bounded strict YAML path and expanded with Bukkit-compatible argument rules, recursion and command-count bounds.
- Audit uses a bounded queue to prevent unbounded memory growth.
- The plugin has no telemetry, database, NMS, reflection, native code, shaded runtime library, or outbound network path.

## Plugin unload boundary

TwiOpSec is loaded through `paper-plugin.yml`, uses Paper's isolated plugin classloader model, and registers commands through the lifecycle/Brigadier API. It denies known and configurable command-layer unload/reload routes targeting itself or all plugins and records unexpected disable. This is useful against mistakes and ordinary PlugMan-style access. It cannot create isolation inside a shared JVM: a malicious plugin can invoke Bukkit/plugin-manager APIs directly, unregister events, rewrite files, or use reflection. Java agents/native code and a compromised server account are stronger still.

Accordingly, absolute “cannot be unloaded” behavior is intentionally not claimed. Hard controls belong outside the plugin: restrict filesystem and process ownership, do not hot-load untrusted plugins, verify JAR hashes, keep online authentication enabled, and restart after any plugin-set change. Paper itself does not treat `/reload` as a supported production lifecycle.

## CVE, injection, crash and dupe scope

TwiOpSec reduces a privilege-escalation/force-op class; it does not parse network packets, inventory transactions, NBT/data components, chat templates, SQL, Redis, proxy messages, or gameplay economy state. Those absent surfaces cannot honestly be described as patched by this plugin. The JAR contains only project classes and Paper-provided API use, reducing dependency and Log4j-style supply-chain exposure.

Minecraft/Paper/Folia/Java and all installed plugins must still be patched independently. Generic crash packets, inventory dupes, world bugs and vulnerabilities wholly outside authorization are not intercepted here.
