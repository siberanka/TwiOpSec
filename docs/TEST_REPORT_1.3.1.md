# TwiOpSec 1.3.1 validation report

Date: 2026-09-15 (Europe/Istanbul)

## Result

The 1.3.1 patch changes protected permission matching so a terminal `.*` remains an exact permission node. An explicit terminal `.**` is the only descendant matcher. This prevents normal child permissions, including purchased Essentials warp permissions, from being removed by the LuckPerms remediation hook.

## Automated verification

- `releaseCheck` completed successfully with compilation warnings treated as errors, tests, JAR assembly and Javadoc validation.
- 48 tests in 16 suites passed with no failures, errors or skips. The production-shaped legacy import test used a private local T2C configuration copy and did not place production identity data in the repository.
- New regressions cover exact `plugin.*` nodes, case-insensitive matches, explicit `plugin.**` descendant boundaries, ordinary `essentials.warps.end` preservation, positive command parsing and LuckPerms node-event persistence.
- The release JAR contains only TwiOpSec classes/resources and no shaded LuckPerms/T2C code.

## Isolated runtime compatibility

The final JAR was started against isolated copies of the deployed Leaf 1.21.4, 1.21.8 and 1.21.11 server binaries. Production worlds and permission databases were not mounted. Legacy T2C files and existing TwiOpSec configuration were mounted read-only; temporary output was discarded.

All tested versions loaded TwiOpSec 1.3.1, completed automatic and explicit T2C import, reported healthy audit state and stopped cleanly. Fresh isolated LuckPerms storage loaded with its native TwiOpSec mutation hook on each target where LuckPerms is deployed. Tests ran without external network access, so update/yggdrasil lookups intentionally reported unavailable and did not affect enforcement.

Before production rollout, LuckPerms exports were inspected without publishing holder identities. Ordinary Essentials child permissions did not match the corrected protected set. Exact protected grants remain subject to removal from groups and untrusted users as designed.

## Artifact

- `TwiOpSec-1.3.1.jar`
- Size: 122,457 bytes
- SHA-256: `6c49d2ba1e1eb9f9e2fadaee455c2c87d15fcf26d7406521a7946f84403a5e8c`

## Limits

The compatibility run did not connect a real player client and did not load production worlds. Same-JVM malicious plugins remain inside the Bukkit/Paper trust boundary; host controls, allowlisted plugins and current server software are still required.

After publication, a production-shaped server with 22 existing Turkish Latin alias names in `commands.yml` rejected those names at startup and stopped under the intended fail-closed policy. The initial isolated test had not mounted that actual alias file. The affected server was restored to its previous hash-verified security JARs, and 1.3.2 corrects both alias loading and parsing with an actual read-only alias-file runtime test. Use 1.3.2 or newer on servers with Turkish aliases.
