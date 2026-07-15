# Public API

Package roots are `de.visualtasker.flowchart.domain`, `.validation`, `.layout`, `.interaction`, `.serialization`, `.compose`, and `.testsupport`.

The primary Compose entry point is:

```kotlin
@Composable
fun FlowchartHost(
    graphDocument: FlowGraphDocument,
    viewDocument: FlowViewDocument?,
    runtimeSnapshot: FlowRuntimeSnapshot?,
    controller: FlowchartController,
    uiConfig: FlowchartUiConfig = FlowchartUiConfig(),
    callbacks: FlowchartHostCallbacks = FlowchartHostCallbacks(),
)
```

Programmatic graph, view, and runtime attachment does not emit a user view-change callback. A committed user view mutation updates controller state first and then emits exactly one callback. Selection/invocation callbacks identify generic node or edge IDs only. `onRunRequested` delegates to the host.

Forbidden by design are source changes, parser/compiler APIs, workflow mutation, authority changes, storage paths, Android `Context`, `SharedPreferences`, and reverse compilation.
