# TwiOpSec 1.3.2 validation report

Date: 2026-09-15 (Europe/Istanbul).

## Incident and containment

The first 1.3.1 Survival startup imported trusted identities but rejected 22 Turkish command aliases in its existing `commands.yml`, then stopped under the documented fail-closed policy. Its worlds saved normally. The original T2C security and library JARs were hash-verified against the stopped-server backup and restored; Survival then returned to a fully running state. No live alias file was altered.

## Fix and verification

- One bounded validator is shared by the alias loader and command parser. Turkish Latin labels work; slash/whitespace and non-Latin lookalikes are rejected. Command namespaces stay ASCII-only.
- `releaseCheck` completed with `-Werror`, Javadoc, JAR assembly and 50 successful automated tests in 16 suites; no failed, skipped or errored tests.
- The actual Survival and BoxPVP `commands.yml` files were mounted read-only into two isolated Leaf runs with fresh plugin storage. Production worlds and LuckPerms storage were not mounted.
- Both isolated runs enabled TwiOpSec 1.3.2, imported legacy trusted identities, initialized the LuckPerms native hook, completed explicit import and status with healthy audit, and stopped cleanly. The isolated network was disabled; unrelated Yggdrasil/update fetch warnings were expected.
- Ordinary Essentials child permission preservation from 1.3.1 and the exact wildcard regressions continue to pass.

## Artifact

- `TwiOpSec-1.3.2.jar`
- Size: 122,572 bytes
- SHA-256: `89203eabfe3dbdf119a3a5d3cd834c18fd475df55334e3229f5b7526bd9e6429`

## Limits

Client login and production-world behavior are verified separately during staged deployment. Third-party plugins sharing the same JVM remain outside this protection's trust boundary.
