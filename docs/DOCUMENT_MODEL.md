# Document Model

`FlowGraphDocument` carries schema, document, producer, and source identity plus stable nodes, edges, diagnostics, source references, and extensions. Semantic kinds have a standard vocabulary and a stable producer extension ID; an unknown extension remains a generic labeled node and is never rewritten as `UNKNOWN_SOURCE`.

`FlowViewDocument` is independently versioned and tied to document ID/revision and surface ID. It stores viewport, positions, sizes, collapse/pin tokens, locked routes, layout metadata, and explicitly non-semantic annotations. Unknown element IDs are diagnosed and quarantined. Discarding a view cannot lose workflow semantics.

`FlowInteractionState` contains selection, hover, tools, drag/pan/marquee previews, and view undo/redo. It is transient and has no default codec.

`FlowRuntimeSnapshot` is qualified by run, source session, document, revision, monotonic sequence, and capture time. Runtime attachment never changes graph or view documents.
