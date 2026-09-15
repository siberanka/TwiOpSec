# TwiOpSec 1.3.0

This security feature release adds immediate protected-permission remediation and a narrowly scoped Citizens server-NPC compatibility option.

## Changes

- Positive protected-permission grants through recognized LuckPerms, PermissionsEx and GroupManager command forms are blocked before dispatch for untrusted users and all groups.
- LuckPerms node additions, loaded users and loaded groups are reconciled through its native API. Normal changes are saved asynchronously with privacy-safe failure reporting; transient changes remain transient. Vault and Bukkit attachments provide bounded best-effort user fallbacks.
- Native-hook settings apply on reload without duplicate subscriptions. Permission-command trust uses canonical UUIDs or resolved online-player UUIDs, not informational names; duplicate Bukkit attachment grants are all removed.
- `compatibility.citizens.server-command-npc-bypass` is disabled by default. When enabled, only an enabled-Citizens-owned player NPC with `NPC=true` metadata can skip the permission-state precheck for exactly one direct `/server <target>` command.
- Citizens metadata impersonation, disabled/missing Citizens, malformed metadata, wrappers, extra arguments and every non-`server` command fail closed. OP, permission-manager, malformed-command and runtime unload guards remain active.

Existing configurations require no migration; omitted settings receive the documented secure defaults. Review group permission design before rollout: a protected positive node is intentionally not allowed on any LuckPerms group because group membership is not a UUID identity boundary.

## Verification

- 46 tests in 16 suites passed, including the private local T2C import fixture, permission mutation parsing, Citizens ownership/command-scope negatives, normal/transient LuckPerms events, storage failures, dynamic flags and duplicate attachments.
- Paper 26.2 build 123 stable and Folia 26.2 build 7 beta loaded TwiOpSec as a Paper plugin after Citizens 2.0.43 build 4250, created a player NPC, attached `server lobby` in NPC execution mode, and shut down cleanly.
- The distributable contains no Citizens, Vault, LuckPerms implementation, T2C bytecode, or shaded third-party library.
- Real Paper and Folia runs verified native LuckPerms group-node removal. Paper also verified persisted removal across restart and disable/re-enable reconciliation on reload. Vault 1.7.3 works on Paper but is refused by Folia for lacking its support declaration; TwiOpSec continues without it.

## Security boundary

The Citizens exception does not make Citizens or another same-JVM plugin a security boundary. A compromised plugin can directly mutate server state; process/filesystem isolation and a trusted-plugin allowlist remain necessary. TwiOpSec does not claim to patch packet, inventory, gameplay-dupe or upstream server vulnerabilities. No real client login/NPC click or long-running multi-region load test was executed; see the test report for the exact verification boundary.
