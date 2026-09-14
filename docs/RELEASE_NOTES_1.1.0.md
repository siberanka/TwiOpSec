# TwiOpSec 1.1.0

This release moves TwiOpSec to Paper's modern plugin and command lifecycle while tightening the administration and runtime-unload boundary.

## Highlights

- Ships `paper-plugin.yml` only and is listed by Paper/Folia as a Paper plugin, not a legacy Bukkit plugin.
- Registers `/twiopsec`, `/twios`, and `/opsec` through the Paper lifecycle/Brigadier API.
- Allows TwiOpSec command execution only from the local server console. RCON, players, command blocks, and other sender types cannot execute them.
- Sends the Vanilla/Paper translatable unknown-command response when a player attempts execution.
- Exposes the command tree and tab completion in game only to a currently active OP whose UUID is in `trusted.operators`.
- Expands configurable PlugMan-style unload/reload protection to nested actions, namespaced/JAR targets, and `--all`, while preserving normal `stop` shutdown.
- Keeps the mandatory permission defaults when importing T2C-OPSecurity data.

## Verified environments

- Paper 26.2 build 123 stable, Java 26: pass; detected as `Paper plugins (1)`.
- Folia 26.2 build 7 beta, Java 26: pass; detected as `Paper plugins (1)`.
- Automated suite: 13 tests in 7 suites, 0 failures/errors/skipped, including an exact private-fixture import comparison.
- Both real servers imported 1 operator, 1 permission identity, and 10 protected permissions; unload/reload forms were blocked and `stop` completed without an unexpected-disable marker.

Release artifact: `TwiOpSec-1.1.0.jar`, 56,234 bytes, SHA-256 `6313e78507ed40449cea06f661d92df9d190e729626a267fff604d9b76ee0176`.

## Upgrade

Stop the server fully, replace the old TwiOpSec JAR, and start the server again. Do not use `/reload` or a plugin manager for this upgrade. Existing TwiOpSec configuration remains valid; built-in plugin-manager command roots are retained even when the new optional list is absent. Review the console-only command policy before removing direct server-console access from administrators.

## Security boundary

Command filtering and Paper classloader isolation make ordinary or accidental hot-unload paths harder, but a malicious plugin already executing inside the same JVM is within the trust boundary and can invoke APIs or reflection directly. TwiOpSec deliberately does not attempt an unsafe self-reenable loop and never obstructs a genuine server shutdown. Use only trusted JARs, least-privilege filesystem/process accounts, online authentication, and patched Java/Paper/Folia versions.

## Attribution

T2C-OPSecurity and T2CodeLib attribution and the clean-room licensing rationale remain documented in `UPSTREAM.md`. No upstream bytecode or unlicensed source is bundled.
