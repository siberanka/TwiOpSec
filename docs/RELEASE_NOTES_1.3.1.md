# TwiOpSec 1.3.1

This patch fixes an over-broad protected-permission match introduced in 1.3.0. A configured `plugin.*` entry is now the exact wildcard permission node, consistent with the migrated T2C configuration model. It no longer matches or removes ordinary child permissions such as `plugin.feature.use`.

Administrators who deliberately need descendant matching can use the explicit `plugin.**` form. Matching remains case-insensitive and regex-free.

Upgrade is strongly recommended before enabling LuckPerms remediation on servers that assign ordinary child permissions below a protected namespace.
