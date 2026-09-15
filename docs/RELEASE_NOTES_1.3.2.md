# TwiOpSec 1.3.2

The 1.3.1 wildcard-permission correction remains in effect. This patch fixes startup on servers with existing Turkish Bukkit command aliases. Version 1.3.1 rejected Turkish Latin alias names in `commands.yml` and intentionally stopped a production server under its fail-closed policy; that server was restored to its original security JARs before this fix was tested.

Alias loading and command parsing now share a bounded label validator that accepts ASCII and Turkish Latin letters. Spaces, slashes, control characters, and non-Latin confusable letters remain invalid; command namespaces remain ASCII-only. Configuration, command alias mappings, trusted identities and permission behavior are otherwise unchanged.

The release passed 50 automated tests and isolated runtime imports against Leaf with the actual read-only BoxPVP and Survival alias files, including LuckPerms hook initialization, status and explicit import. No production worlds or LuckPerms databases were mounted in those isolated tests.
