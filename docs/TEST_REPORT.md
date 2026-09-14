# TwiOpSec 1.1.1 test report

Date: 2026-09-14 (Europe/Istanbul)

## Scope and result

The clean-room implementation, transactional T2C import, strict configuration path, permission-pattern parsing, command and alias normalization, Paper/Folia startup, runtime command guard, audit recovery/rotation, and graceful shutdown passed the executed checks. Production files were read only; tests staged private copies under the ignored `server-test/` directory. No player name or UUID is included in this repository or report.

## Automated tests

Command executed:

```powershell
gradle clean test jar javadoc -PlegacyT2Dir='<private-local-source>' --offline --warning-mode all --no-daemon
```

Result: 25 tests in 10 suites, 0 failures, 0 errors, 0 skipped. Compilation used `--release 25`, `-Xlint:all`, and `-Werror`; Javadoc validation completed without warnings after excluding missing-comment lint. Java toolchain: local Temurin 25.0.2. Gradle launcher: 8.14.3 on local Java 21.

Regression coverage includes exact/wildcard permission parsing, mandatory-default retention, strict types/schema/UTF-8/YAML bounds, multiline/oversized command rejection, namespace normalization, nested/JAR/`--all` unload matching, Bukkit alias placeholder/recursion handling, console-only execution policy, whitelist-OP command visibility, default unknown-command translation, incomplete-import rollback, corrupt marker rejection, collision-safe backups, last-known-good behavior, audit draining/escaping/rotation, and exact preservation of the production whitelist UUID sets. JaCoCo measured 59.7% line and 47.7% branch coverage; security-critical parsers have targeted regression tests in addition to this aggregate metric.

## Real-server smoke matrix

| Server | Runtime | Import | Guard checks | Shutdown | Result |
|---|---|---:|---|---|---|
| Paper `26.2 build 123 stable` | Azul Zulu Java 26.0.1 | `Paper plugins (1)`, 1 operator, 1 permission identity, 10 protected permissions | Direct/nested OP, direct reload, and fixed/parameterized/nested alias routes blocked; stale stored OP removed; malformed reload retained active settings; LKG recovery and no-valid-config fail-closed shutdown passed | `stop`, exit 0; forced fail-closed exit 0 | Pass |
| Folia `26.2 build 7 beta` | Azul Zulu Java 26.0.1 | `Paper plugins (1)`, 1 operator, 1 permission identity, 10 protected permissions | Same direct/nested/alias privilege and reload probes blocked; stale stored OP removed on the global scheduler; no fake OP state remained | `stop`, exit 0 with region scheduler halt | Pass |

Both final servers bound only to `127.0.0.1`, selected an ephemeral port, allowed one player, disabled RCON/query/status and used an isolated flat world. No attack was sent to a third-party system. A pre-fix Paper reproduction demonstrated that server aliases were a real secondary dispatch path; the final JAR blocked every corresponding probe on both Paper and Folia. Paper itself normalizes duplicate `commands.yml` keys before plugin enable, while the loader's direct duplicate rejection is covered by a unit test.

The local Java trust store rejected the environment's intercepted TLS certificate when Mojang/version checks ran. This did not originate from TwiOpSec, which performs no network requests, and did not prevent isolated offline smoke tests. Official server artifacts were downloaded outside Java and SHA-256 verified before execution.

## Artifact checks

- JAR classes are exclusively under `com/siberanka/twiopsec`.
- No T2C bytecode, shaded dependency, credentials, production YAML, server log, or test world is present in the artifact.
- Local release-candidate `TwiOpSec-1.1.1.jar`: 80,776 bytes; SHA-256 `b3116e0efec5e21881a9556a2397c4078909d604f6e60339967d15988a032907`.
- Folia server artifact SHA-256: `128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`.

## Not executed / limitations

- No real player client or compromised third-party plugin was loaded, so network command-tree delivery and kick/deop behavior were not exercised end-to-end through a login. Command visibility and unknown-response policy were verified with API-level sender tests.
- No 30–60 minute soak, fuzz campaign, mutation test, packet-level exploit suite, or multi-region two-player load test was run.
- Only Minecraft 26.2 was claimed and tested; no backward-compatibility claim is made.
- The final 1.1.1 JAR was re-run on Paper and Folia. The earlier Leaf 26.2 smoke result remains historical 1.1.0 evidence and was not repeated for this patch.
- The imported production identity and permission data were compared programmatically but intentionally not printed.
- Same-JVM malicious code cannot be made an absolute security boundary by a Bukkit plugin; see `SECURITY_MODEL.md`.
