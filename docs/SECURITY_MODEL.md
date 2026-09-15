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
10. Update metadata is accepted only from the exact GitHub release path, or from the exact GitLab release path after a GitHub failure; no returned redirect is followed.
11. Positive protected-permission grants are denied before known permission-manager commands execute; LuckPerms API mutations are removed from their original normal/transient map immediately. Normal changes are submitted to asynchronous persistence; transient changes are never persisted.
12. Protected group nodes are never retained because group membership is not an identity trust boundary; trusted user UUIDs remain exempt.
13. The optional Citizens exception applies only to enabled-Citizens-owned `NPC=true` metadata while that player NPC executes one direct `/server <target>` command. Exactly one conservative target token is required; wrappers, extra arguments, embedded commands and every other root fail closed.

## Defensive controls

- No regex is used for untrusted permission patterns. Nodes, including a terminal `.*` permission grant, are exact. Only an explicit terminal `.**` matches descendants. This prevents broad defaults such as `essentials.*` from consuming ordinary nodes such as `essentials.warps.end`.
- Imported files must be regular files below the selected plugins folder and at most 2 MiB.
- Import creates backup copies, hashes inputs, atomically replaces the target configuration, and writes its commit marker last.
- Enforcement command templates are length/count bounded and reject embedded newlines.
- Settings are immutable snapshots exchanged atomically; reload does not mutate a live configuration object.
- Server aliases are read through the same bounded strict YAML path and expanded with Bukkit-compatible argument rules, recursion and command-count bounds.
- Audit uses a bounded queue to prevent unbounded memory growth.
- The plugin has no telemetry, database, NMS, native code, or shaded runtime library. Minimal reflection is used only to keep the optional Vault API absent-safe. Its only outbound path is a bounded asynchronous metadata request to fixed GitHub/GitLab release endpoints; it closes the response without consuming a release body and never downloads or executes an update.

## Permission-provider boundary

Permission-command targets are trusted only by canonical allowlisted UUID or by an actual online player's resolved UUID. An informational whitelist name cannot authorize an offline name grant. No group is UUID-exempt. The native LuckPerms hook reconciles loaded data on startup and configuration reload; normal and transient additions are checked separately. Reload changes the active flags without adding duplicate subscriptions.

LuckPerms storage failure retains the in-memory removal, logs a fixed severe warning and records a privacy-safe audit event. Persistence is not guaranteed when the provider's storage fails; fix storage health before restarting. Vault and Bukkit attachment fallbacks are bounded best effort, not a guarantee of removing inherited/provider-owned permissions. Vault 1.7.3 is not Folia-supported and is refused by Folia itself; the native LuckPerms hook remains available without it. No third-party plugin descriptor is modified to bypass Folia's loader check.

## Update notification boundary

GitHub is authoritative. A syntactically valid GitHub release response is final even when it reports the same or an older version; GitLab cannot override it. GitLab is queried only after an I/O/timeout or strict validation failure. Accepted URLs require HTTPS, the default/443 port, no userinfo/query/fragment, the exact `siberanka/TwiOpSec` release path, and a stable `MAJOR.MINOR.PATCH` tag. The client does not follow redirects, has bounded connect/request timeouts, and runs on Paper's async scheduler.

An available update is logged to the console and sent at join only to a player who is both currently OP and UUID-trusted at delivery time. The clickable target is the already validated release page. Notice state is cleared on quit so each new join is notified; completion/join races are deduplicated. Update-check failure does not weaken or stop privilege enforcement.

## Plugin unload boundary

TwiOpSec is loaded through `paper-plugin.yml`, uses Paper's isolated plugin classloader model, and registers commands through the lifecycle/Brigadier API. It denies known and configurable command-layer unload/reload routes targeting itself or all plugins and records unexpected disable. This is useful against mistakes and ordinary PlugMan-style access. It cannot create isolation inside a shared JVM: a malicious plugin can invoke Bukkit/plugin-manager APIs directly, unregister events, rewrite files, or use reflection. Java agents/native code and a compromised server account are stronger still.

Accordingly, absolute “cannot be unloaded” behavior is intentionally not claimed. Hard controls belong outside the plugin: restrict filesystem and process ownership, do not hot-load untrusted plugins, verify JAR hashes, keep online authentication enabled, and restart after any plugin-set change. Paper itself does not treat `/reload` as a supported production lifecycle.

## CVE, injection, crash and dupe scope

TwiOpSec reduces a privilege-escalation/force-op class; it does not parse network packets, inventory transactions, NBT/data components, chat templates, SQL, Redis, proxy messages, or gameplay economy state. Those absent surfaces cannot honestly be described as patched by this plugin. The JAR contains only project classes and Paper-provided API use, reducing dependency and Log4j-style supply-chain exposure.

Minecraft/Paper/Folia/Java and all installed plugins must still be patched independently. Generic crash packets, inventory dupes, world bugs and vulnerabilities wholly outside authorization are not intercepted here.
