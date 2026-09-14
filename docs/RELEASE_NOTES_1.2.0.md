# TwiOpSec 1.2.0

This feature release adds a hardened update-notification path without automatic update installation.

## Changes

- GitHub is the authoritative release source; GitLab is queried only after a GitHub transport or validation failure.
- Only fixed HTTPS hosts, the default secure port, exact `siberanka/TwiOpSec` release paths and stable Semantic Version tags are accepted.
- Redirects are inspected but never followed; response bodies are not downloaded.
- Connect/request timeouts are bounded to 2–15 seconds and all network work runs on Paper's async scheduler.
- A newer version is logged during server startup and shown as a clickable release link whenever a trusted active OP joins.
- Join/check races are deduplicated while quit/rejoin deliberately permits a fresh notice.
- `/twiopsec status` exposes `checking`, source, available version, disabled or bounded failure state.

No configuration migration is required. Existing configurations use safe defaults. To opt out, set `updates.enabled: false`; the source URLs are intentionally not configurable.

## Verification

- 32 tests in 12 suites, including primary/fallback priority, malicious redirects, stable version ordering, trusted-OP notification eligibility/deduplication, strict settings and the private local T2C production import fixture.
- Paper 26.2 build 123 stable and Folia 26.2 build 7 beta both loaded it as a Paper plugin and completed clean shutdown.
- Both failed-certificate and valid live-GitHub paths were exercised. Update-network failure did not affect privilege enforcement.

## Security boundary

TwiOpSec only reports a validated release page. It never downloads, replaces or executes a JAR. A compromised plugin in the same JVM remains inside the server trust boundary; see `docs/SECURITY_MODEL.md`.
