# Third-party notices

TwiOpSec's distributable JAR contains no copied T2C source and no shaded third-party library. Paper API, the MIT-licensed LuckPerms API, and JUnit are build/test dependencies only and are not bundled. Citizens (OSL 3.0) and Vault are optional runtime integrations supplied separately by the server owner; neither project is copied, modified, shaded, or redistributed by TwiOpSec.

Upstream compatibility research and authorship credits are recorded in [UPSTREAM.md](UPSTREAM.md). Gradle Wrapper files are provided under Gradle's licensing terms; see the [Gradle project](https://github.com/gradle/gradle) for its Apache License 2.0 notice.

- LuckPerms API: copyright lucko and contributors; [MIT license](https://github.com/LuckPerms/LuckPerms/blob/master/LICENSE.txt), [official API documentation](https://luckperms.net/wiki/Developer-API-Usage). Only API references are compiled; the API/implementation is not bundled.
- Citizens: CitizensDev and contributors; [OSL 3.0 license](https://github.com/CitizensDev/Citizens2/blob/master/LICENSE), [official metadata API documentation](https://wiki.citizensnpcs.co/API). The optional compatibility check uses Bukkit metadata; no Citizens source or API artifact is included.
