# M217 VisualTasker Studio Integration Handoff

This is the only public document that refers to Studio-specific integration.

M217 should add the public repository/artifacts without changing Workflow Domain authority. VisualTasker Studio remains responsible for EMScript projection, source/display selection, storage location/timing, project association, runtime event production, plugin registration, and optional run requests.

Required adapters:

1. Map the existing Studio producer projection into `FlowGraphDocument` with stable IDs, source revision/hash, producer diagnostics, and generic source spans.
2. Migrate or safely ignore existing v1/v2 Flowchart view preferences; unknown/stale IDs must never create semantic nodes.
3. Qualify runtime events with run ID, source-session ID, document ID/revision, and monotonic sequence.
4. Map Studio colors/chrome/callbacks to `FlowchartUiConfig` and `FlowchartHostCallbacks`.
5. Keep the existing renderer selectable as fallback through parity testing.

M217 must not remove parser fallbacks, reverse mapping, the old renderer, or Blockly coupling. Those remain M218 work after visual, layout, routing, persistence, runtime, and device parity gates pass. Native Blockeditor pin, Browser behavior, Vision taxonomy, project persistence, and one exclusive semantic write authority remain unchanged.
