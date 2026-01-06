# Dependencies

- Minecraft targets: **1.21.10** and **1.21.11** (Fabric and NeoForge); pinned per target in `gradle.properties` (`mc_*` entries).
- Discord: **Javacord 3.8.x**.
- Fabric API / loader versions pinned per target in `gradle.properties`; NeoForge via NeoGradle userdev with per-target pins.
- Testing: **JUnit 5.11**.
- Formatting: ktlint via Spotless (see Formatting & Style).

Do not bump versions without review; keep pins in `gradle.properties` synchronized across targets.
