# TwiOpSec 1.3.0 test report

Date: 2026-09-14–15 (Europe/Istanbul)

## Scope and result

The clean-room implementation, transactional T2C import, strict configuration path, permission-pattern and mutation parsing, command and alias normalization, update-source validation/fallback, Citizens ownership/scoping, Paper/Folia startup, runtime command guard, LuckPerms/Vault discovery, audit recovery/rotation, and graceful shutdown passed the executed checks. Production files were read only; tests staged private copies under the ignored `server-test/` directory. No production player name or UUID is included in this repository or report; public test identities are synthetic.

## Automated tests

Command executed:

```powershell
gradle clean test jar javadoc -PlegacyT2Dir='<private-local-source>' --offline --warning-mode all --no-daemon
```

Result: 46 tests in 16 suites, 0 failures, 0 errors, 0 skipped. Compilation used `--release 25`, `-Xlint:all`, and `-Werror`; Javadoc validation completed without warnings after excluding missing-comment lint. Java toolchain: local Temurin 25.0.2. Gradle launcher: 8.14.3 on local Java 21.

Regression coverage includes exact/wildcard permission parsing, positive/removal/false permission mutations, action-position ambiguity, mandatory-default retention, strict types/schema/UTF-8/YAML bounds, multiline/oversized command rejection, namespace normalization, nested/JAR/`--all` unload matching, Bukkit alias placeholder/recursion handling, console-only execution policy, whitelist-OP command visibility, default unknown-command translation, Citizens plugin ownership, metadata impersonation/error, exact `/server <target>` shape and nested-command negatives, incomplete-import rollback, corrupt marker rejection, collision-safe backups, last-known-good behavior, audit draining/escaping/rotation, exact preservation of the production whitelist UUID sets, primary/fallback update priority, malicious release redirects, full stable version ordering, trusted-active-OP eligibility, per-session deduplication and clickable URL content. Additional provider tests cover normal/transient maps, trusted UUID versus informational name, user/group persistence, synchronous/asynchronous storage failures, dynamic native flags and duplicate Bukkit attachments. JaCoCo measured 59.0% line and 49.4% branch coverage; security-critical parsers and trust decisions have targeted regression tests in addition to this aggregate metric.

## Real-server smoke matrix

| Server | Runtime | Import | Guard checks | Shutdown | Result |
|---|---|---:|---|---|---|
| Paper `26.2 build 123 stable` | Azul Zulu Java 26.0.1 | TwiOpSec 1.3.0 Paper plugin; Citizens 2.0.43-b4250, LuckPerms 5.5.65 and Vault 1.7.3 loaded first | Citizens opt-in loaded; player NPC creation and `server lobby` NPC command attachment succeeded. Protected LP group grant was blocked before dispatch; with only the command-grant layer disabled, the native event removed it and LP returned `undefined`, also after restart. Native flag off + reload allowed the isolated grant; flag on + reload reconciled it to `undefined` without duplicate hook registration. | `stop`, exit 0; final artifact/provider run closed cleanly | Pass |
| Folia `26.2 build 7 beta` | Azul Zulu Java 26.0.1 | TwiOpSec 1.3.0 Paper plugin; Citizens 2.0.43-b4250 and LuckPerms 5.5.65 loaded first | Citizens opt-in loaded; player NPC creation and `server lobby` NPC command attachment succeeded. Native LP group event removed the isolated protected grant; LP returned `undefined`, audit healthy/current GitHub source. Vault 1.7.3 was refused by Folia for lacking Folia support; final rerun omitted that isolated Vault JAR. | `stop`, exit 0 with region scheduler halt | Pass |

Both final servers bound only to `127.0.0.1`, selected an ephemeral port, allowed one player, disabled RCON/query/status and used an isolated flat world. No attack was sent to a third-party system. The Citizens artifact came from its official Jenkins build 4250 and included the 26.2 adapter. The production LuckPerms/Vault configuration and databases were not copied; only JARs were staged and fresh isolated data was generated. A deliberately immediate shutdown during an asynchronous harmless LuckPerms command produced a third-party H2 close race; the controlled rerun waited for completion and shut down cleanly.

The default local Java trust store rejected the environment's intercepted TLS certificate for Mojang, GitHub and GitLab. TwiOpSec tried the backup, reported `unavailable` without exposing exception content, and continued enforcement. Repeating both Paper and Folia tests with Java's Windows root-store provider exercised the valid live GitHub response and produced `update=up-to-date,source=github`. The current local build was newer than the then-published release, so the player-facing available-update message was not exercised through a real login; source choice and newer-version decisions were exercised with deterministic unit probes.

## Artifact checks

- JAR classes are exclusively under `com/siberanka/twiopsec`.
- No T2C bytecode, shaded dependency, credentials, production YAML, server log, or test world is present in the artifact.
- Final `TwiOpSec-1.3.0.jar`: 122,238 bytes; SHA-256 `b933e680e1b19ff870ebedc9d7dcc8b157dc1c118c31cc74133141178b09b03a`.
- Folia server artifact SHA-256: `128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3`.

## Not executed / limitations

- No real player client or compromised third-party plugin was loaded, so network command-tree delivery, NPC click dispatch, and kick/deop behavior were not exercised end-to-end through a login. Command visibility, unknown-response policy, exact NPC metadata ownership and command scope were verified with API-level sender tests; real servers verified Citizens load, NPC creation and command attachment.
- No 30–60 minute soak, fuzz campaign, mutation test, packet-level exploit suite, or multi-region two-player load test was run.
- Only Minecraft 26.2 was claimed and tested; no backward-compatibility claim is made.
- The final 1.3.0 JAR was re-run on Paper and Folia with a valid live GitHub update path. The earlier failed-certificate paths and Leaf 26.2 smoke result remain historical evidence and were not repeated for this feature release.
- The imported production identity and permission data were compared programmatically but intentionally not printed.
- Same-JVM malicious code cannot be made an absolute security boundary by a Bukkit plugin; see `SECURITY_MODEL.md`.
