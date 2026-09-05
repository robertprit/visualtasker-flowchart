/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.layout.*
import de.visualtasker.flowchart.validation.*

public enum class FlowchartStatusCode { ATTACHED, INVALID_GRAPH, STALE_VIEW_DISCARDED, RUNTIME_ATTACHED, RUNTIME_REJECTED, CLOSED }
public data class FlowchartStatus(public val code: FlowchartStatusCode, public val message: String, public val diagnostics: List<FlowValidationDiagnostic> = emptyList())
public data class FlowchartControllerState(public val graph: FlowGraphDocument?, public val view: FlowViewDocument?, public val runtime: FlowRuntimeSnapshot?, public val interaction: FlowInteractionState, public val closed: Boolean)

public class FlowchartController(
    private val surfaceId: FlowSurfaceId,
    private val nodeMetrics: FlowNodeMetrics = FlowNodeMetrics(emptyMap()),
    private val layoutConfig: FlowLayoutConfig = FlowLayoutConfig(),
) : AutoCloseable {
    private val lock: Any = Any()
    private var state: FlowchartControllerState = FlowchartControllerState(null, null, null, FlowInteractionState(), false)
    private var generation: Long = 0
    private var viewListener: ((FlowViewDocument) -> Unit)? = null
    private var statusListener: ((FlowchartStatus) -> Unit)? = null

    public fun snapshot(): FlowchartControllerState = synchronized(lock) { state }
    public fun setListeners(onViewChanged: ((FlowViewDocument) -> Unit)?, onStatus: ((FlowchartStatus) -> Unit)?) { synchronized(lock) { if (!state.closed) { viewListener = onViewChanged; statusListener = onStatus } } }

    public fun attachGraph(graph: FlowGraphDocument, view: FlowViewDocument? = null): FlowchartStatus {
        val validation = FlowGraphValidator.validate(graph)
        if (!validation.isValid) return publishStatus(FlowchartStatus(FlowchartStatusCode.INVALID_GRAPH, "Graph rejected", validation.diagnostics))
        val currentGeneration = synchronized(lock) { if (state.closed) return FlowchartStatus(FlowchartStatusCode.CLOSED, "Controller is closed"); ++generation }
        val viewValidation = view?.let { FlowViewValidator.validate(graph, it) }
        val compatible = view != null && viewValidation?.diagnostics?.none { it.code == FlowValidationCode.GRAPH_IDENTITY_MISMATCH || it.code == FlowValidationCode.REVISION_MISMATCH } == true
        val canRebaseStaleView = view != null && !compatible && viewValidation?.canRebaseAcrossRevision() == true
        val installedView = when {
            compatible -> FlowViewValidator.quarantineUnknown(graph, view!!)
            canRebaseStaleView -> rebaseViewForGraph(graph, view!!)
            else -> layoutView(graph)
        }
        val status = FlowchartStatus(
            when {
                view != null && !compatible && !canRebaseStaleView -> FlowchartStatusCode.STALE_VIEW_DISCARDED
                else -> FlowchartStatusCode.ATTACHED
            },
            when {
                compatible -> "Graph and view attached"
                canRebaseStaleView -> "Graph and stale view rebased"
                else -> "Graph attached with deterministic view"
            },
            viewValidation?.diagnostics.orEmpty().filterNot {
                canRebaseStaleView && it.code == FlowValidationCode.REVISION_MISMATCH
            },
        )
        synchronized(lock) {
            if (state.closed || generation != currentGeneration) return FlowchartStatus(FlowchartStatusCode.CLOSED, "Attachment superseded")
            state = FlowchartControllerState(graph, installedView, null, FlowInteractionState(), false)
        }
        return publishStatus(status)
    }

    public fun attachRuntime(snapshot: FlowRuntimeSnapshot): FlowchartStatus {
        val (graph, previous) = synchronized(lock) { state.graph to state.runtime }
        if (graph == null) return publishStatus(FlowchartStatus(FlowchartStatusCode.RUNTIME_REJECTED, "No graph attached"))
        val validation = FlowRuntimeSnapshotValidator.validate(graph, snapshot, FlowRuntimeValidationContext(previous = previous))
        if (!validation.isValid) return publishStatus(FlowchartStatus(FlowchartStatusCode.RUNTIME_REJECTED, "Runtime rejected", validation.diagnostics))
        synchronized(lock) { if (!state.closed) state = state.copy(runtime = snapshot) else return FlowchartStatus(FlowchartStatusCode.CLOSED, "Controller is closed") }
        return publishStatus(FlowchartStatus(FlowchartStatusCode.RUNTIME_ATTACHED, "Runtime attached"))
    }

    public fun dispatch(action: FlowInteractionAction): FlowInteractionResult? {
        val callback: ((FlowViewDocument) -> Unit)?
        val result: FlowInteractionResult
        synchronized(lock) {
            val graph = state.graph ?: return null; val view = state.view ?: return null
            if (state.closed) return null
            result = FlowInteractionReducer.reduce(state.interaction, action, graph, view)
            state = state.copy(view = result.view, interaction = result.state)
            callback = if (result.viewChanged) viewListener else null
        }
        callback?.invoke(result.view)
        return result
    }

    public fun replaceViewport(viewport: FlowViewport): FlowViewDocument? {
        require(viewport.zoom.isFinite() && viewport.zoom > 0.0)
        require(viewport.pan.x.isFinite() && viewport.pan.y.isFinite())
        val view: FlowViewDocument
        synchronized(lock) {
            if (state.closed) return null
            val current = state.view ?: return null
            view = current.copy(viewport = viewport)
            state = state.copy(view = view)
        }
        return view
    }

    public fun replaceLayout(config: FlowLayoutConfig = layoutConfig): FlowViewDocument? {
        val callback: ((FlowViewDocument) -> Unit)?
        val graph = synchronized(lock) { if (state.closed) return null else state.graph } ?: return null
        val layout = FlowLayoutEngine.layout(graph, nodeMetrics, config, state.view)
        val newView = state.view?.copy(nodeViews = graph.visibleLayoutNodes().map { node -> val bounds = layout.nodeBounds[node.id] ?: return@map FlowNodeView(node.id, FlowPoint(0.0, 0.0)); FlowNodeView(node.id, bounds.origin, bounds.size) }, edgeViews = layout.routes.values.map { route -> FlowEdgeView(route.edgeId, route.points.drop(1).dropLast(1).map { it.asPoint() }) }, layoutMetadata = FlowLayoutMetadata("hierarchical", "1", config.deterministicSeed)) ?: return null
        synchronized(lock) { if (state.closed) return null; state = state.copy(view = newView); callback = viewListener }
        callback?.invoke(newView)
        return newView
    }

    public fun cancelTransientInteraction() {
        synchronized(lock) {
            if (state.closed) return
            val view = state.view
            val drag = state.interaction.dragState
            val pan = state.interaction.panState
            val restoredView = when {
                view == null -> null
                drag != null -> view.copy(
                    nodeViews = view.nodeViews.map { node ->
                        drag.originalPositions[node.nodeId]?.let { node.copy(position = it) } ?: node
                    }
                )
                pan != null -> view.copy(viewport = view.viewport.copy(pan = pan.originalPan))
                else -> view
            }
            state = state.copy(
                view = restoredView,
                interaction = state.interaction.copy(
                    dragState = null,
                    panState = null,
                    marqueeState = null,
                    temporaryRoutePreview = null,
                )
            )
        }
    }

    private fun layoutView(graph: FlowGraphDocument): FlowViewDocument {
        val layout = FlowLayoutEngine.layout(graph, nodeMetrics, layoutConfig)
        return FlowViewDocument(documentId = graph.documentId, compatibleDocumentRevision = graph.documentRevision, surfaceId = surfaceId, nodeViews = graph.visibleLayoutNodes().map { node -> val bounds = layout.nodeBounds[node.id] ?: FlowRect(FlowPoint(0.0, 0.0), nodeMetrics.defaultSize); FlowNodeView(node.id, bounds.origin, bounds.size) }, edgeViews = layout.routes.values.map { route -> FlowEdgeView(route.edgeId, route.points.drop(1).dropLast(1).map { it.asPoint() }) }, layoutMetadata = FlowLayoutMetadata("hierarchical", "1", layoutConfig.deterministicSeed))
    }

    private fun rebaseViewForGraph(graph: FlowGraphDocument, view: FlowViewDocument): FlowViewDocument {
        val baseline = layoutView(graph)
        val knownNodes = view.nodeViews.associateBy { it.nodeId }
        val knownEdges = view.edgeViews.associateBy { it.edgeId }
        return FlowViewValidator.quarantineUnknown(graph, view).copy(
            compatibleDocumentRevision = graph.documentRevision,
            nodeViews = baseline.nodeViews.map { nodeView -> knownNodes[nodeView.nodeId] ?: nodeView },
            edgeViews = baseline.edgeViews.map { edgeView -> knownEdges[edgeView.edgeId] ?: edgeView },
            layoutMetadata = baseline.layoutMetadata,
        )
    }

    private fun FlowValidationResult.canRebaseAcrossRevision(): Boolean =
        diagnostics.none { diagnostic ->
            diagnostic.severity == FlowValidationSeverity.ERROR &&
                diagnostic.code != FlowValidationCode.REVISION_MISMATCH
        }

    private fun publishStatus(status: FlowchartStatus): FlowchartStatus { val callback = synchronized(lock) { if (state.closed) null else statusListener }; callback?.invoke(status); return status }
    override fun close() { synchronized(lock) { if (!state.closed) { generation++; state = state.copy(runtime = null, closed = true); viewListener = null; statusListener = null } } }
}

private fun FlowGraphDocument.visibleLayoutNodes(): List<FlowGraphNode> =
    nodes.filterNot { node ->
        node.properties["visualFacet"] == FlowSemanticValue.BooleanValue(true) &&
            node.properties["syntheticJoin"] != FlowSemanticValue.BooleanValue(true)
    }
