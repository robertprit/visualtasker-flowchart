# Runtime Overlays

Runtime snapshots attach only when document ID and revision match. For an existing run, run ID, source-session ID, and monotonic sequence must remain compatible; stale sequences are rejected with typed diagnostics. Unknown active nodes, runtime nodes, and traversed edges are rejected.

The overlay can display not-started, queued, running, waiting, succeeded, failed, skipped, and cancelled states plus runtime diagnostics and loop labels. Shape/stroke/text semantics supplement color. Runtime state never mutates graph or durable view state, and the library never executes the run.
