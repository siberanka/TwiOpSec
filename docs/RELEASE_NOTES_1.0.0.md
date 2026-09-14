# TwiOpSec 1.0.0

Initial public release of the clean-room Paper/Folia privilege guard and T2C configuration migration path.

## Highlights

- Separate UUID-backed trust lists for operators and protected permissions.
- Mandatory defaults retained during import: `*`, `minecraft.*`, `minecraft.command.*`, `minecraft.command.op`, `bukkit.command.*`, `paper.command.*`, `essentials.*`, `luckperms.*`, and `twiopsec.admin`.
- Transactional, idempotent T2C-OPSecurity migration with bounded YAML input, backups, hashes, and atomic config replacement.
- One runtime JAR with no T2CodeLib dependency and no shaded libraries.
- Folia entity/global scheduler ownership, immutable live settings, and bounded asynchronous JSONL audit.
- Guarding of unauthorized `/op`, configured privilege-manager roots, known command-layer plugin unload/reload routes, and unsupported server reload; normal server `stop` is preserved.

## Verified environments

- Paper 26.2 build 123 stable, Java 26: pass.
- Folia 26.2 build 7 beta, Java 26: pass.
- Leaf 26.2 build 46 alpha, Java 26: smoke pass.
- Automated suite: 9 tests, 0 failed/skipped, including an opt-in exact import comparison against a private production fixture.

Release artifact: `TwiOpSec-1.0.0.jar`, 54,537 bytes, SHA-256 `3c24a6667d6066266a69f1dd7cd1d4b463a2d3972674cdb788d0d5b986a774c0`.

## Migration and rollback

Stop the server, retain the old `plugins/T2C-OPSecurity/` directory, install TwiOpSec, then verify import counts and both UUID trust lists before removing old JARs. Migration backups are stored below `plugins/TwiOpSec/migration-backups/`. To roll back, stop the server and restore both the old JARs and their untouched data directories; do not delete the TwiOpSec migration backup until verification is complete.

## Security boundary

TwiOpSec narrows operator and permission escalation paths; it is not an anti-cheat, packet firewall, or universal Minecraft CVE/dupe fix. A malicious plugin already executing in the same JVM is inside the trust boundary and can bypass command listeners through direct API/reflection. Keep Java, Paper/Folia, and all plugins patched and load only verified JARs. See `SECURITY.md` and `docs/SECURITY_MODEL.md` before deployment.

## Attribution

T2C-OPSecurity and T2CodeLib are credited in `UPSTREAM.md`. Their reviewed repositories declared no software license, so their source was not copied or bundled; TwiOpSec is an independent MIT implementation of the migration/configuration contract.
