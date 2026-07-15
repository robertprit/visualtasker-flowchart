# Architecture

## Ownership

The library owns generic interchange models, validation, deterministic layout/routing, durable view documents, transient interaction state, canonical serialization, Compose rendering, controller lifecycle, and reusable fixtures. It owns neither producer semantics nor workflow authority.

## Module boundaries

`flowchart-domain` has no dependencies beyond Kotlin serialization annotations. Validation, layout, interaction, and serialization are pure Kotlin/JVM consumers of domain contracts. `flowchart-compose` is the only reusable Android boundary. `flowchart-test-support` uses public modules only. `demo-app` is a standalone Android consumer.

Dependency direction is acyclic. Producers attach a complete derived `FlowGraphDocument`; the public host never accepts source text. Host applications retain storage, project association, permissions, runtime execution, and backup/recovery.

## Determinism and lifecycle

Pure validators, codecs, layout, routing, hit testing, transforms, and reducers are side-effect-free. `FlowchartController` commits state under a private lock and invokes callbacks only after leaving the lock. Graph generations prevent superseded attachment from committing. Closing detaches listeners and runtime state and prevents later callbacks.

## Security boundary

Documents are untrusted input. Typed validation precedes attachment; unsupported major schemas are rejected. The library does not evaluate source, resolve files, access a network, or execute producer instructions.
