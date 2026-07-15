/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.*

public enum class FlowLayoutOrientation { TOP_TO_BOTTOM, LEFT_TO_RIGHT }
public enum class FlowSyntheticNodePolicy { INCLUDE, EXCLUDE_ANNOTATIONS }
public enum class FlowPinnedNodePolicy { HONOR_VIEW, IGNORE }

public data class FlowLayoutConfig(
    public val orientation: FlowLayoutOrientation = FlowLayoutOrientation.TOP_TO_BOTTOM,
    public val layerSpacing: Double = 96.0,
    public val nodeSpacing: Double = 48.0,
    public val componentSpacing: Double = 144.0,
    public val routingClearance: Double = 16.0,
    public val crossingReductionSweeps: Int = 4,
    public val deterministicSeed: Long = 0L,
    public val syntheticNodePolicy: FlowSyntheticNodePolicy = FlowSyntheticNodePolicy.INCLUDE,
    public val pinnedNodePolicy: FlowPinnedNodePolicy = FlowPinnedNodePolicy.HONOR_VIEW,
) {
    init {
        require(layerSpacing > 0 && nodeSpacing > 0 && componentSpacing > 0 && routingClearance >= 0)
        require(crossingReductionSweeps >= 0)
    }
}

public data class FlowNodeMetrics(public val sizes: Map<FlowNodeId, FlowSize>, public val defaultSize: FlowSize = FlowSize(160.0, 72.0))
public enum class FlowRouteKind { ORTHOGONAL, LOOP_BACK, BRANCH, DIRECT_FALLBACK }
public data class FlowRoutePoint(public val x: Double, public val y: Double) { public fun asPoint(): FlowPoint = FlowPoint(x, y) }
public data class FlowRouteSegment(public val start: FlowRoutePoint, public val end: FlowRoutePoint)
public data class FlowRoute(public val edgeId: FlowEdgeId, public val kind: FlowRouteKind, public val points: List<FlowRoutePoint>, public val segments: List<FlowRouteSegment>, public val usesInternalDummyPoints: Boolean = false)
public enum class FlowLayoutDiagnosticCode { INVALID_GRAPH, NON_FINITE_OUTPUT, ROUTE_FALLBACK }
public data class FlowLayoutDiagnostic(public val code: FlowLayoutDiagnosticCode, public val message: String, public val edgeId: FlowEdgeId? = null)

public data class FlowLayoutResult(
    public val nodeBounds: Map<FlowNodeId, FlowRect>,
    public val routes: Map<FlowEdgeId, FlowRoute>,
    public val ranks: Map<FlowNodeId, Int>,
    public val backEdgeIds: Set<FlowEdgeId>,
    public val diagnostics: List<FlowLayoutDiagnostic>,
    public val internalDummyPointCount: Int,
) {
    public val isValid: Boolean get() = diagnostics.none { it.code == FlowLayoutDiagnosticCode.INVALID_GRAPH || it.code == FlowLayoutDiagnosticCode.NON_FINITE_OUTPUT }
}
