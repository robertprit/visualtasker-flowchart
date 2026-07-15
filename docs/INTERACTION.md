# Interaction

The pure reducer supports node/edge selection, toggle and clear, single or connected-BFS node drag, cancel/commit, viewport pan, anchor-preserving zoom, marquee selection, and view undo/redo. Preview updates are not committed callbacks; commit emits one durable view change.

Platform-neutral `FlowPoint`, `FlowSize`, and `FlowRect` back hit testing and transforms. Node hits take priority over edge segment-distance hits. The Compose boundary translates to Compose geometry only while drawing and receiving gestures.

Semantic editing is disabled and cannot be enabled in the current public configuration.
