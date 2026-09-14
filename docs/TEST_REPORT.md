# TwiOpSec 1.0.0 test report

Date: 2026-09-14 (Europe/Istanbul)

## Scope and result

The clean-room implementation, transactional T2C import, permission-pattern parsing, command normalization, Paper/Folia startup, runtime command guard, and graceful shutdown passed the executed checks. Production files were read only; tests staged private copies under the ignored `server-test/` directory. No player name or UUID is included in this repository or report.

## Automated tests

Command executed:

```powershell
gradle clean test jar -PlegacyT2Dir='<private-local-source>' --offline --warning-mode all
```

Result: 9 tests in 4 suites, 0 failures, 0 errors, 0 skipped. Compilation used `--release 25`, `-Xlint:all`, and `-Werror`. Java toolchain: Eclipse Temurin 25.0.3; Gradle launcher: Java 21.0.9; Gradle 9.1.0.

Regression coverage includes exact/wildcard permission parsing, multiline/oversized command rejection, namespace normalization, unload/reload matching without blocking `stop` or `minecraft:reload`, incomplete-import rollback, idempotent marker behavior, preservation of mandatory permissions, and exact preservation of the production whitelist UUID sets.

## Real-server smoke matrix

| Server | Runtime | Import | Guard checks | Shutdown | Result |
|---|---|---:|---|---|---|
| Paper `26.2 build 123 stable` | Azul Zulu Java 26.0.1 | 1 operator, 1 permission identity, 10 protected permissions | PlugManX-style unload blocked | `stop`, exit 0, no unexpected marker | Pass |
| Folia `26.2 build 7 beta` | Azul Zulu Java 26.0.1 | 1 operator, 1 permission identity, 10 protected permissions | PlugManX-style unload and `/reload` blocked | `stop`, exit 0, no unexpected marker | Pass |
| Leaf `26.2 build 46 alpha` | Azul Zulu Java 26.0.1 | 1 operator, 1 permission identity, 10 protected permissions | PlugMan-style unload and `/reload` blocked | `stop`, exit 0 | Pass |

All servers bound only to `127.0.0.1`, selected an ephemeral port, allowed one player, disabled RCON/query/status and used an isolated flat world. No attack was sent to a third-party system.

The local Java trust store rejected the environment's intercepted TLS certificate when Mojang/version checks ran. This did not originate from TwiOpSec, which performs no network requests, and did not prevent isolated offline smoke tests. Official server artifacts were downloaded outside Java and SHA-256 verified before execution.

## Artifact checks

- JAR classes are exclusively under `com/siberanka/twiopsec`.
- No T2C bytecode, shaded dependency, credentials, production YAML, server log, or test world is present in the artifact.
- Clean-checkout `TwiOpSec-1.0.0.jar` size: 54,537 bytes; SHA-256: `3c24a6667d6066266a69f1dd7cd1d4b463a2d3972674cdb788d0d5b986a774c0`.
- Folia server artifact SHA-256: `128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`.

## Not executed / limitations

- No real player client or compromised third-party plugin was loaded, so kick/deop behavior was not exercised end-to-end through a login.
- No 30–60 minute soak, fuzz campaign, mutation test, packet-level exploit suite, or multi-region two-player load test was run.
- Only Minecraft 26.2 was claimed and tested; no backward-compatibility claim is made.
- The imported production identity and permission data were compared programmatically but intentionally not printed.
- Same-JVM malicious code cannot be made an absolute security boundary by a Bukkit plugin; see `SECURITY_MODEL.md`.
