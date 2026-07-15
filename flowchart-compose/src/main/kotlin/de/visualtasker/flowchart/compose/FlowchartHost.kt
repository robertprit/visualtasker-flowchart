/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*

@Composable
public fun FlowchartHost(
    graphDocument: FlowGraphDocument,
    viewDocument: FlowViewDocument?,
    runtimeSnapshot: FlowRuntimeSnapshot?,
    controller: FlowchartController,
    uiConfig: FlowchartUiConfig = FlowchartUiConfig(),
    callbacks: FlowchartHostCallbacks = FlowchartHostCallbacks(),
) {
    var controllerState by remember(controller) { mutableStateOf(controller.snapshot()) }
    DisposableEffect(controller, callbacks) {
        controller.setListeners(
            { callbacks.onViewDocumentChanged(it); controllerState = controller.snapshot() },
            { callbacks.onStatusMessage(it); controllerState = controller.snapshot() },
        )
        onDispose { controller.setListeners(null, null) }
    }
    LaunchedEffect(graphDocument, viewDocument) { controller.attachGraph(graphDocument, viewDocument); controllerState = controller.snapshot() }
    LaunchedEffect(runtimeSnapshot) { runtimeSnapshot?.let(controller::attachRuntime); controllerState = controller.snapshot() }
    val view = controllerState.view
    if (graphDocument.nodes.isEmpty() || view == null) {
        Box(Modifier.fillMaxSize().testTag("flowchart-empty").semantics { contentDescription = "Empty flowchart" }) { Text("No flowchart nodes", Modifier.padding(24.dp)) }
        return
    }
    Box(Modifier.fillMaxSize().background(uiConfig.colorTokens.background)) {
        FlowCanvas(graphDocument, view, controllerState.runtime, controllerState.interaction, controller, uiConfig, callbacks) { controllerState = controller.snapshot() }
        ZoomControls(controller, uiConfig) { controllerState = controller.snapshot() }
        NodeSemantics(graphDocument, view, controllerState, callbacks)
    }
}

@Composable
private fun FlowCanvas(graph: FlowGraphDocument, view: FlowViewDocument, runtime: FlowRuntimeSnapshot?, interaction: FlowInteractionState, controller: FlowchartController, config: FlowchartUiConfig, callbacks: FlowchartHostCallbacks, refresh: () -> Unit) {
    var dragNode by remember { mutableStateOf<FlowNodeId?>(null) }
    val modifier = Modifier.fillMaxSize().testTag("flowchart-canvas")
        .pointerInput(graph, view, config) { detectTapGestures(onDoubleTap = { offset -> hitNode(offset, view)?.let(callbacks.onNodeInvoked) }, onTap = { offset ->
            if (!config.selectionEnabled) return@detectTapGestures
            val node = hitNode(offset, view)
            if (node != null) { controller.dispatch(FlowInteractionAction.SelectNode(node)); callbacks.onNodeSelected(node) } else { controller.dispatch(FlowInteractionAction.ClearSelection); callbacks.onNodeSelected(null) }
            refresh()
        }) }
        .pointerInput(graph, view, config.nodeDraggingEnabled) { if (config.nodeDraggingEnabled) detectDragGestures(
            onDragStart = { offset -> dragNode = hitNode(offset, view); dragNode?.let { controller.dispatch(FlowInteractionAction.BeginNodeDrag(it, FlowPoint(offset.x.toDouble(), offset.y.toDouble()))) } },
            onDrag = { change, _ -> dragNode?.let { controller.dispatch(FlowInteractionAction.UpdateNodeDrag(FlowPoint(change.position.x.toDouble(), change.position.y.toDouble()))); refresh() } },
            onDragEnd = { dragNode?.let { controller.dispatch(FlowInteractionAction.CommitNodeDrag); refresh() }; dragNode = null },
            onDragCancel = { controller.dispatch(FlowInteractionAction.CancelNodeDrag); dragNode = null; refresh() },
        ) }
    Canvas(modifier) {
        val viewport = view.viewport
        fun screen(point: FlowPoint) = Offset((point.x * viewport.zoom + viewport.pan.x).toFloat(), (point.y * viewport.zoom + viewport.pan.y).toFloat())
        graph.edges.sortedBy { it.id.value }.forEach { edge ->
            val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return@forEach
            val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return@forEach
            val sourceSize = source.size ?: FlowSize(160.0, 72.0); val targetSize = target.size ?: FlowSize(160.0, 72.0)
            val start = FlowPoint(source.position.x + sourceSize.width / 2, source.position.y + sourceSize.height)
            val end = FlowPoint(target.position.x + targetSize.width / 2, target.position.y)
            val bends = view.edgeViews.firstOrNull { it.edgeId == edge.id }?.bendPoints.orEmpty()
            (listOf(start) + bends + end).map(::screen).zipWithNext().forEach { (a, b) -> drawLine(config.colorTokens.edge, a, b, 2.dp.toPx()) }
        }
        graph.nodes.sortedBy { it.id.value }.forEach { node ->
            val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowSize(160.0, 72.0); val origin = screen(nodeView.position); val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            val runtimeState = runtime?.nodeStates?.get(node.id)
            val stroke = when { node.id in interaction.selectedNodeIds -> config.colorTokens.selectedStroke; runtimeState == FlowRuntimeNodeState.FAILED -> config.colorTokens.failedStroke; runtimeState in setOf(FlowRuntimeNodeState.RUNNING, FlowRuntimeNodeState.WAITING) -> config.colorTokens.runningStroke; else -> config.colorTokens.nodeStroke }
            drawRoundRect(config.colorTokens.nodeFill, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()))
            drawRoundRect(stroke, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()), style = Stroke(config.shapeTokens.nodeStrokeWidthDp.dp.toPx(), pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) drawCircle(config.colorTokens.diagnostic, 6.dp.toPx(), Offset(origin.x + canvasSize.width - 10.dp.toPx(), origin.y + 10.dp.toPx()))
        }
    }
}

@Composable private fun ZoomControls(controller: FlowchartController, config: FlowchartUiConfig, refresh: () -> Unit) {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (config.zoomEnabled) {
            Button({ controller.dispatch(FlowInteractionAction.ZoomViewport(1.2, FlowPoint(0.0, 0.0))); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.zoomIn }) { Text("+") }
            Button({ controller.dispatch(FlowInteractionAction.ZoomViewport(1 / 1.2, FlowPoint(0.0, 0.0))); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.zoomOut }) { Text("−") }
        }
        Button({ controller.attachGraph(controller.snapshot().graph ?: return@Button, null); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.centerView }) { Text("Center") }
    }
}

@Composable private fun NodeSemantics(graph: FlowGraphDocument, view: FlowViewDocument, state: FlowchartControllerState, callbacks: FlowchartHostCallbacks) {
    Box(Modifier.fillMaxSize()) { graph.nodes.forEach { node ->
        val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
        val runtime = state.runtime?.nodeStates?.get(node.id)
        Box(Modifier.offset(nodeView.position.x.dp, nodeView.position.y.dp).size((nodeView.size?.width ?: 160.0).dp, (nodeView.size?.height ?: 72.0).dp).semantics {
            contentDescription = buildString { append(node.label); append(", "); append(node.kind.displayName ?: node.kind.standard?.name ?: "extension node"); if (runtime != null) { append(", "); append(runtime.name) }; if (node.diagnosticIds.isNotEmpty()) append(", has diagnostics") }
            selected = node.id in state.interaction.selectedNodeIds
            onClick("Select node") { callbacks.onNodeSelected(node.id); true }
            onLongClick("Invoke node") { callbacks.onNodeInvoked(node.id); true }
        })
    } }
}

private fun hitNode(offset: Offset, view: FlowViewDocument): FlowNodeId? {
    val graphPoint = FlowViewportTransform.screenToGraph(FlowPoint(offset.x.toDouble(), offset.y.toDouble()), view.viewport)
    return view.nodeViews.asReversed().firstOrNull { FlowRect(it.position, it.size ?: FlowSize(160.0, 72.0)).contains(graphPoint) }?.nodeId
}
