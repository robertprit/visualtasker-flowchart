# Compatibility and Versioning

The library follows semantic versioning and starts at `0.1.0-SNAPSHOT`. After 1.0, breaking public API changes require a major release. Before 1.0, every breaking change requires an explicit changelog entry.

Graph, view, and runtime schema versions evolve independently from the library. Unknown major versions fail. Compatible minor additions use defaults or extension values. Serialized migrations are explicit hooks, not implicit decoder behavior.

Android modules currently require min SDK 29, compile SDK 36, Java 17, Kotlin 2.0.21, and compatible Compose tooling. Pure modules require Java 17 and do not expose Android types.
