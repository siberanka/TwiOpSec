# Changelog

All notable changes use Semantic Versioning.

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
