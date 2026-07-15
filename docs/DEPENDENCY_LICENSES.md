# Direct Dependency License Audit

Audit date: 2026-07-15. The repository redistributes no third-party source and therefore has no project `NOTICE` file.

| Dependency family | Scope | License | Redistribution note |
|---|---|---|---|
| Kotlin Gradle plugins and standard library | build/runtime | Apache-2.0 | Compatible |
| kotlinx.serialization JSON | runtime | Apache-2.0 | Compatible |
| Android Gradle Plugin | build | Apache-2.0 | Build tooling only |
| AndroidX Activity and Compose | Android runtime | Apache-2.0 | Compatible |
| JUnit 4 | tests only | EPL-1.0 | Not shipped in library or demo artifacts |

No GPL, AGPL, SSPL, Commons Clause, Business Source License, or source-available dependency is declared. Transitive resolution is checked in CI through the public repositories configured in `settings.gradle.kts`; additions require updating this inventory.
