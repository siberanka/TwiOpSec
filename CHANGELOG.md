# Changelog

All notable changes use Semantic Versioning.

## 1.3.1 - 2026-09-15

- Fixed protected permission matching so a configured `plugin.*` protects only that actual wildcard grant instead of deleting every ordinary child permission.
- Added explicit `plugin.**` descendant matching for administrators who intentionally need namespace-wide protection.
- Added regression coverage for ordinary Essentials-style child permissions, exact wildcard grants, case handling and descendant boundary matching.

## 1.3.0 - 2026-09-15

- Added immediate LuckPerms node-mutation monitoring for protected user and group permissions.
- Added automatic native removal and persistence of unauthorized protected nodes on add, user load, startup group reconciliation and player checks.
- Added Vault and Bukkit attachment fallbacks for other permission providers without granting either fallback authority to remove groups blindly.
- Blocked positive protected-permission grants through LuckPerms, PermissionsEx and GroupManager command forms before execution, including wrapped and aliased command chains.
- Added an opt-in Citizens exception restricted to enabled-Citizens-owned `NPC=true` metadata and one direct `/server <target>` command; wrappers, extra arguments, OP, permission-manager and unload protections remain enforced.
- Added negative tests for removal commands, false nodes, harmless permissions, metadata impersonation and non-`/server` NPC commands.
- Reconcile normal and transient LuckPerms maps separately; persist only normal nodes and report storage failures without restoring privileges or exposing storage exceptions.
- Reload native-hook flags without duplicate subscriptions; permission-command trust uses canonical UUIDs or an actual online player's UUID, never an informational whitelist name.
- Remove duplicate protected grants across separate Bukkit attachments and eliminate the unused VaultAPI/JitPack build dependency.

## 1.2.0 - 2026-09-14

- Added an asynchronous, bounded startup update check with GitHub as the authoritative source and GitLab as failure-only fallback.
- Restricted accepted release links to exact HTTPS hosts, ports, repository paths and stable Semantic Version tags; redirects are never followed automatically.
- Added console startup notices and clickable join notices only for active operators whose UUID is trusted.
- Added update state to the console status command and safe timeout/disable settings.
- Added regression coverage for fallback priority, malicious redirects, source pinning and version ordering.

## 1.1.1 - 2026-09-14

- Closed nested `execute`/dispatch command bypasses for OP targets, privilege roots and runtime reload/unload commands.
- Closed Bukkit `commands.yml` alias bypasses, including fixed, parameterized, nested and cyclic aliases.
- Added startup reconciliation for unauthorized entries already present in the persistent operator registry.
- Replaced lenient YAML loading with bounded strict parsing, type/schema validation and duplicate/alias/tag rejection.
- Added last-known-good runtime configuration recovery and fail-closed server shutdown when no valid configuration exists.
- Made legacy migration marker validation, backups and config/marker rollback transactional and collision-safe.
- Retained mandatory privilege-manager roots and made runtime unload command blocking non-disableable.
- Cancelled violating interactions and added early/final command-event enforcement passes.
- Added rotating, retrying audit output with health/drop visibility in the console status command.
- Added regression tests for malformed configs/imports, corrupt markers, nested command chains and audit rotation.

## 1.1.0 - 2026-09-14

- Migrated from legacy `plugin.yml` loading to the modern Paper plugin descriptor and lifecycle command registration.
- Restricted all TwiOpSec command execution to the local server console; RCON, players and command blocks cannot execute them.
- Limited in-game command-tree and tab-completion visibility to active operators whose UUID is in `trusted.operators`.
- Returned the Vanilla/Paper translatable unknown-command response for any player execution attempt.
- Expanded runtime unload/reload detection with configurable plugin-manager command roots, nested actions, JAR targets and `--all` forms.
- Retained clean `stop` behavior and unexpected-disable evidence without attempting unsafe self-reenable during shutdown.

## 1.0.0 - 2026-09-14

- Added UUID-backed, separate operator and protected-permission trust lists.
- Added mandatory high-impact permission defaults retained across migration.
- Added transactional and idempotent T2C-OPSecurity YAML import with backups and hashes.
- Added Paper/Folia-safe scheduling, bounded asynchronous audit logging, and command hardening.
- Added command-layer protection against unauthorized `/op`, privilege managers, runtime unload and unsupported server reload while preserving clean shutdown.
- Removed the need for a T2CodeLib runtime by implementing required internal facilities in one JAR.
- Documented clean-room origin, upstream attribution, threat boundaries, rollback, and verified test matrix.
