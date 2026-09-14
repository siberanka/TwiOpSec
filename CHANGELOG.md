# Changelog

All notable changes use Semantic Versioning.

## 1.0.0 - 2026-09-14

- Added UUID-backed, separate operator and protected-permission trust lists.
- Added mandatory high-impact permission defaults retained across migration.
- Added transactional and idempotent T2C-OPSecurity YAML import with backups and hashes.
- Added Paper/Folia-safe scheduling, bounded asynchronous audit logging, and command hardening.
- Added command-layer protection against unauthorized `/op`, privilege managers, runtime unload and unsupported server reload while preserving clean shutdown.
- Removed the need for a T2CodeLib runtime by implementing required internal facilities in one JAR.
- Documented clean-room origin, upstream attribution, threat boundaries, rollback, and verified test matrix.
