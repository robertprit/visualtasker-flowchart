# Layout Engine

The hierarchical engine normalizes producer ordering, discovers connected components, classifies DFS back edges, assigns ranks, orders layers deterministically, assigns coordinates, creates internal long-edge routing points, produces orthogonal routes, packs components, and validates finite output.

Inputs include orientation, spacing, routing clearance, crossing sweeps, deterministic seed, synthetic-node policy, pinned-node policy, and node metrics. Supported orientations are top-to-bottom and left-to-right. Back edges use an explicit exterior loop lane; branch edges retain branch route kinds. Locked view routes override automatic bends without mutating the graph.

The current crossing reduction is a deterministic predecessor-rank sweep. It favors stability and reproducibility over globally optimal crossing count. Collision fallback diagnostics are part of the public result model.
