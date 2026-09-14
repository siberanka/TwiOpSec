# TwiOpSec 1.2.0 test report

Date: 2026-09-14 (Europe/Istanbul)

## Scope and result

The clean-room implementation, transactional T2C import, strict configuration path, permission-pattern parsing, command and alias normalization, update-source validation/fallback, Paper/Folia startup, runtime command guard, audit recovery/rotation, and graceful shutdown passed the executed checks. Production files were read only; tests staged private copies under the ignored `server-test/` directory. No player name or UUID is included in this repository or report.

## Automated tests

Command executed:

```powershell
gradle clean test jar javadoc -PlegacyT2Dir='<private-local-source>' --offline --warning-mode all --no-daemon
```

Result: 32 tests in 12 suites, 0 failures, 0 errors, 0 skipped. Compilation used `--release 25`, `-Xlint:all`, and `-Werror`; Javadoc validation completed without warnings after excluding missing-comment lint. Java toolchain: local Temurin 25.0.2. Gradle launcher: 8.14.3 on local Java 21.

Regression coverage includes exact/wildcard permission parsing, mandatory-default retention, strict types/schema/UTF-8/YAML bounds, multiline/oversized command rejection, namespace normalization, nested/JAR/`--all` unload matching, Bukkit alias placeholder/recursion handling, console-only execution policy, whitelist-OP command visibility, default unknown-command translation, incomplete-import rollback, corrupt marker rejection, collision-safe backups, last-known-good behavior, audit draining/escaping/rotation, exact preservation of the production whitelist UUID sets, primary/fallback update priority, malicious release redirects, full stable version ordering, trusted-active-OP eligibility, per-session deduplication and clickable URL content. JaCoCo measured 58.1% line and 47.3% branch coverage; security-critical parsers and update trust decisions have targeted regression tests in addition to this aggregate metric.

## Real-server smoke matrix

| Server | Runtime | Import | Guard checks | Shutdown | Result |
|---|---|---:|---|---|---|
| Paper `26.2 build 123 stable` | Azul Zulu Java 26.0.1 | `Paper plugins (1)`, 1 operator, 1 permission identity, 10 protected permissions | Direct/nested OP, direct reload, and fixed/parameterized/nested alias routes blocked; stale stored OP removed; malformed reload retained active settings; update failure remained non-fatal; live GitHub path reported current | `stop`, exit 0; forced fail-closed exit 0 | Pass |
| Folia `26.2 build 7 beta` | Azul Zulu Java 26.0.1 | `Paper plugins (1)`, 1 operator, 1 permission identity, 10 protected permissions | Same direct/nested/alias privilege and reload probes blocked; stale stored OP removed on the global scheduler; update failure remained non-fatal; live GitHub path reported current | `stop`, exit 0 with region scheduler halt | Pass |

Both final servers bound only to `127.0.0.1`, selected an ephemeral port, allowed one player, disabled RCON/query/status and used an isolated flat world. No attack was sent to a third-party system. A pre-fix Paper reproduction demonstrated that server aliases were a real secondary dispatch path; the final JAR blocked every corresponding probe on both Paper and Folia. Paper itself normalizes duplicate `commands.yml` keys before plugin enable, while the loader's direct duplicate rejection is covered by a unit test.

The default local Java trust store rejected the environment's intercepted TLS certificate for Mojang, GitHub and GitLab. TwiOpSec tried the backup, reported `unavailable` without exposing exception content, and continued enforcement. Repeating both Paper and Folia tests with Java's Windows root-store provider exercised the valid live GitHub response and produced `update=up-to-date,source=github`. The current local build was newer than the then-published release, so the player-facing available-update message was not exercised through a real login; source choice and newer-version decisions were exercised with deterministic unit probes.

## Artifact checks

- JAR classes are exclusively under `com/siberanka/twiopsec`.
- No T2C bytecode, shaded dependency, credentials, production YAML, server log, or test world is present in the artifact.
- Local release-candidate `TwiOpSec-1.2.0.jar`: 99,414 bytes; SHA-256 `7d5f41672bf86646fd1d1d042085894cd1c4d7601b5b38e20f8361417c004697`.
- Folia server artifact SHA-256: `128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`.

## Not executed / limitations

- No real player client or compromised third-party plugin was loaded, so network command-tree delivery and kick/deop behavior were not exercised end-to-end through a login. Command visibility and unknown-response policy were verified with API-level sender tests.
- No 30–60 minute soak, fuzz campaign, mutation test, packet-level exploit suite, or multi-region two-player load test was run.
- Only Minecraft 26.2 was claimed and tested; no backward-compatibility claim is made.
- The final 1.2.0 JAR was re-run on Paper and Folia with both failed and successful update-network paths. The earlier Leaf 26.2 smoke result remains historical 1.1.0 evidence and was not repeated for this feature release.
- The imported production identity and permission data were compared programmatically but intentionally not printed.
- Same-JVM malicious code cannot be made an absolute security boundary by a Bukkit plugin; see `SECURITY_MODEL.md`.
