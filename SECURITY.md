# Security policy

## Supported versions

Only the latest TwiOpSec release is supported with security fixes. Paper/Folia and Java must also be on supported, patched releases.

## Reporting

Do not publish exploit steps, server data, credentials, UUID lists, logs containing player identities, or a proof of concept in a public issue. Use a private security advisory on the GitHub repository, or contact the maintainer privately through the account listed on the repository.

Include the TwiOpSec version, server implementation/build, Java version, minimal reproduction, impact, and sanitized logs. Test only systems you own or are explicitly authorized to assess.

## Scope

In scope: authorization bypass, unsafe legacy import, command parsing bypass, Folia thread-ownership violations, denial of service caused by TwiOpSec, audit injection, and secrets or player data accidentally shipped by this project.

Out of scope as a TwiOpSec guarantee: compromise of the operating-system or server account; arbitrary code execution by another plugin already loaded into the same JVM; vulnerabilities in Minecraft, Java, Paper/Folia, or third-party plugins that do not cross a TwiOpSec trust decision; and generic gameplay dupes unrelated to OP/permission protection.
