/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
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
    LaunchedEffect(controller, graphDocument, viewDocument) { controller.attachGraph(graphDocument, viewDocument); controllerState = controller.snapshot() }
    LaunchedEffect(controller, runtimeSnapshot) { runtimeSnapshot?.let(controller::attachRuntime); controllerState = controller.snapshot() }
    val view = controllerState.view
    if (graphDocument.nodes.isEmpty() || view == null) {
        Box(Modifier.fillMaxSize().testTag("flowchart-empty").semantics { contentDescription = "Empty flowchart" }) { Text("No flowchart nodes", Modifier.padding(24.dp)) }
        return
    }
    Box(Modifier.fillMaxSize().background(uiConfig.colorTokens.background)) {
        FlowCanvas(graphDocument, view, controllerState.runtime, controllerState.interaction, uiConfig)
        FlowLabelsAndSemantics(graphDocument, view, controllerState, callbacks)
        FlowGestureLayer(graphDocument, view, controller, uiConfig, callbacks) { controllerState = controller.snapshot() }
        ZoomControls(controller, uiConfig) { controllerState = controller.snapshot() }
    }
}

@Composable
private fun FlowCanvas(graph: FlowGraphDocument, view: FlowViewDocument, runtime: FlowRuntimeSnapshot?, interaction: FlowInteractionState, config: FlowchartUiConfig) {
    Canvas(Modifier.fillMaxSize().testTag("flowchart-canvas")) {
        val viewport = view.viewport
        fun screen(point: FlowPoint) = Offset((point.x * viewport.zoom + viewport.pan.x).toFloat(), (point.y * viewport.zoom + viewport.pan.y).toFloat())
        graph.edges.sortedBy { it.id.value }.forEach { edge ->
            val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return@forEach
            val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return@forEach
            val sourceSize = source.size ?: FlowSize(160.0, 72.0); val targetSize = target.size ?: FlowSize(160.0, 72.0)
            val start = FlowPoint(source.position.x + sourceSize.width / 2, source.position.y + sourceSize.height)
            val end = FlowPoint(target.position.x + targetSize.width / 2, target.position.y)
            val bends = view.edgeViews.firstOrNull { it.edgeId == edge.id }?.bendPoints.orEmpty()
            val points = (listOf(start) + bends + end).map(::screen)
            val edgeColor = when (edge.kind) {
                FlowEdgeKind.TRUE_BRANCH,
                FlowEdgeKind.FALSE_BRANCH,
                FlowEdgeKind.ELSE_IF_BRANCH -> config.colorTokens.branchEdge
                FlowEdgeKind.LOOP_BODY,
                FlowEdgeKind.LOOP_BACK,
                FlowEdgeKind.LOOP_EXIT -> config.colorTokens.loopEdge
                FlowEdgeKind.ERROR,
                FlowEdgeKind.CATCH_BODY -> config.colorTokens.errorEdge
                else -> config.colorTokens.edge
            }
            points.zipWithNext().forEach { (a, b) ->
                drawLine(
                    color = edgeColor,
                    start = a,
                    end = b,
                    strokeWidth = config.shapeTokens.edgeStrokeWidthDp.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            points.firstOrNull()?.let { startPoint ->
                drawCircle(edgeColor, config.shapeTokens.connectorRadiusDp.dp.toPx(), startPoint)
            }
            val arrow = flowArrowHead(
                points = points,
                length = config.shapeTokens.arrowLengthDp.dp.toPx().toDouble(),
                width = config.shapeTokens.arrowWidthDp.dp.toPx().toDouble(),
            )
            if (arrow.size == 3) {
                drawPath(
                    path = Path().apply {
                        moveTo(arrow[0].x.toFloat(), arrow[0].y.toFloat())
                        lineTo(arrow[1].x.toFloat(), arrow[1].y.toFloat())
                        lineTo(arrow[2].x.toFloat(), arrow[2].y.toFloat())
                        close()
                    },
                    color = edgeColor,
                )
            }
        }
        graph.nodes.sortedBy { it.id.value }.forEach { node ->
            val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowSize(160.0, 72.0); val origin = screen(nodeView.position); val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            val runtimeState = runtime?.nodeStates?.get(node.id)
            val stroke = when { node.id in interaction.selectedNodeIds -> config.colorTokens.selectedStroke; runtimeState == FlowRuntimeNodeState.FAILED -> config.colorTokens.failedStroke; runtimeState in setOf(FlowRuntimeNodeState.RUNNING, FlowRuntimeNodeState.WAITING) -> config.colorTokens.runningStroke; else -> config.colorTokens.nodeStroke }
            val visualPath = config.nodeShapeProvider?.pathFor(node, canvasSize.width, canvasSize.height)
            if (visualPath != null) {
                translate(origin.x, origin.y) {
                    drawPath(visualPath, config.colorTokens.nodeFill)
                    drawPath(
                        path = visualPath,
                        color = stroke,
                        style = Stroke(
                            width = config.shapeTokens.nodeStrokeWidthDp.dp.toPx(),
                            pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) {
                                PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                            } else {
                                null
                            },
                        ),
                    )
                }
            } else {
                drawRoundRect(config.colorTokens.nodeFill, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()))
                drawRoundRect(stroke, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()), style = Stroke(config.shapeTokens.nodeStrokeWidthDp.dp.toPx(), pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            }
            if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) drawCircle(config.colorTokens.diagnostic, 6.dp.toPx(), Offset(origin.x + canvasSize.width - 10.dp.toPx(), origin.y + 10.dp.toPx()))
        }
    }
}

internal fun flowArrowHead(
    points: List<Offset>,
    length: Double,
    width: Double,
): List<FlowPoint> {
    if (points.size < 2 || !length.isFinite() || !width.isFinite() || length <= 0.0 || width <= 0.0) {
        return emptyList()
    }
    val tip = points.last()
    val previous = points.asReversed().drop(1).firstOrNull { it != tip } ?: return emptyList()
    val dx = (tip.x - previous.x).toDouble()
    val dy = (tip.y - previous.y).toDouble()
    val magnitude = kotlin.math.hypot(dx, dy)
    if (!magnitude.isFinite() || magnitude <= 0.0) return emptyList()
    val unitX = dx / magnitude
    val unitY = dy / magnitude
    val baseX = tip.x - unitX * length
    val baseY = tip.y - unitY * length
    val halfWidth = width / 2.0
    val perpendicularX = -unitY * halfWidth
    val perpendicularY = unitX * halfWidth
    return listOf(
        FlowPoint(tip.x.toDouble(), tip.y.toDouble()),
        FlowPoint(baseX + perpendicularX, baseY + perpendicularY),
        FlowPoint(baseX - perpendicularX, baseY - perpendicularY),
    )
}

@Composable
private fun FlowGestureLayer(graph: FlowGraphDocument, view: FlowViewDocument, controller: FlowchartController, config: FlowchartUiConfig, callbacks: FlowchartHostCallbacks, refresh: () -> Unit) {
    var dragNode by remember { mutableStateOf<FlowNodeId?>(null) }
    var panning by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    var previousTapAt by remember { mutableLongStateOf(0L) }
    var previousTapPosition by remember { mutableStateOf<Offset?>(null) }
    val modifier = Modifier.fillMaxSize().testTag("flowchart-gestures")
        .pointerInput(graph, config) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val dragStart = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (dragStart != null) {
                    dragNode = if (config.nodeDraggingEnabled) hitNode(down.position, currentView) else null
                    if (dragNode != null) {
                        controller.dispatch(FlowInteractionAction.BeginNodeDrag(dragNode!!, FlowPoint(down.position.x.toDouble(), down.position.y.toDouble())))
                    } else if (config.panEnabled) {
                        panning = true
                        controller.dispatch(FlowInteractionAction.BeginViewportPan(FlowPoint(down.position.x.toDouble(), down.position.y.toDouble())))
                    }
                    val completed = drag(dragStart.id) { change ->
                        if (change.positionChange() != Offset.Zero) change.consume()
                        val point = FlowPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        if (dragNode != null) controller.dispatch(FlowInteractionAction.UpdateNodeDrag(point))
                        else if (panning) controller.dispatch(FlowInteractionAction.UpdateViewportPan(point))
                        refresh()
                    }
                    if (completed) {
                        if (dragNode != null) controller.dispatch(FlowInteractionAction.CommitNodeDrag)
                        else if (panning) controller.dispatch(FlowInteractionAction.CommitViewportPan)
                    } else if (dragNode != null) {
                        controller.dispatch(FlowInteractionAction.CancelNodeDrag)
                    }
                    dragNode = null
                    panning = false
                    refresh()
                } else if (config.selectionEnabled) {
                    val offset = down.position
                    val prior = previousTapPosition
                    val isDoubleTap = down.uptimeMillis - previousTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                        prior != null && (offset - prior).getDistance() <= viewConfiguration.touchSlop
                    previousTapAt = down.uptimeMillis
                    previousTapPosition = offset
                    val node = hitNode(offset, currentView)
                    if (isDoubleTap) {
                        node?.let(callbacks.onNodeInvoked)
                    } else {
                        val edge = if (node == null && config.edgeSelectionEnabled) hitEdge(offset, graph, currentView) else null
                        when {
                            node != null -> { controller.dispatch(FlowInteractionAction.SelectNode(node)); callbacks.onNodeSelected(node); callbacks.onEdgeSelected(null) }
                            edge != null -> { controller.dispatch(FlowInteractionAction.SelectEdge(edge)); callbacks.onNodeSelected(null); callbacks.onEdgeSelected(edge) }
                            else -> { controller.dispatch(FlowInteractionAction.ClearSelection); callbacks.onNodeSelected(null); callbacks.onEdgeSelected(null) }
                        }
                    }
                    refresh()
                }
            }
        }
    Box(modifier)
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

@Composable private fun FlowLabelsAndSemantics(graph: FlowGraphDocument, view: FlowViewDocument, state: FlowchartControllerState, callbacks: FlowchartHostCallbacks) {
    val density = LocalDensity.current
    fun xDp(value: Double) = with(density) { value.toFloat().toDp() }
    Box(Modifier.fillMaxSize()) {
        graph.edges.forEach { edge ->
            val label = edge.label ?: when (edge.kind) { FlowEdgeKind.TRUE_BRANCH -> "TRUE"; FlowEdgeKind.FALSE_BRANCH -> "FALSE"; FlowEdgeKind.ELSE_IF_BRANCH -> "ELSE IF"; FlowEdgeKind.LOOP_BACK -> "LOOP"; else -> null } ?: return@forEach
            val points = edgeScreenPoints(edge, graph, view)
            val center = points.getOrNull(points.size / 2) ?: return@forEach
            Text(label, Modifier.offset(xDp(center.x), xDp(center.y)).semantics { contentDescription = "Edge $label"; selected = edge.id in state.interaction.selectedEdgeIds; onClick("Select edge") { callbacks.onEdgeSelected(edge.id); true } }, style = MaterialTheme.typography.labelSmall)
        }
        graph.nodes.forEach { node ->
        val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
        val runtime = state.runtime?.nodeStates?.get(node.id)
        val screen = FlowViewportTransform.graphToScreen(nodeView.position, view.viewport)
        val width = (nodeView.size?.width ?: 160.0) * view.viewport.zoom
        val height = (nodeView.size?.height ?: 72.0) * view.viewport.zoom
        Box(Modifier.offset(xDp(screen.x), xDp(screen.y)).size(xDp(width), xDp(height)).padding(8.dp).semantics {
            contentDescription = buildString { append(node.label); append(", "); append(node.kind.displayName ?: node.kind.standard?.name ?: "extension node"); if (runtime != null) { append(", "); append(runtime.name) }; if (node.diagnosticIds.isNotEmpty()) append(", has diagnostics") }
            selected = node.id in state.interaction.selectedNodeIds
            onClick("Select node") { callbacks.onNodeSelected(node.id); true }
            onLongClick("Invoke node") { callbacks.onNodeInvoked(node.id); true }
        }) { Column { Text(node.label, style = MaterialTheme.typography.bodyMedium); Text(node.kind.displayName ?: node.kind.standard?.name ?: "Extension", style = MaterialTheme.typography.labelSmall); runtime?.let { Text(it.name, style = MaterialTheme.typography.labelSmall) } } }
    } }
}

private fun hitNode(offset: Offset, view: FlowViewDocument): FlowNodeId? {
    val graphPoint = FlowViewportTransform.screenToGraph(FlowPoint(offset.x.toDouble(), offset.y.toDouble()), view.viewport)
    return view.nodeViews.asReversed().firstOrNull { FlowRect(it.position, it.size ?: FlowSize(160.0, 72.0)).contains(graphPoint) }?.nodeId
}

private fun hitEdge(offset: Offset, graph: FlowGraphDocument, view: FlowViewDocument): FlowEdgeId? {
    val point = FlowPoint(offset.x.toDouble(), offset.y.toDouble())
    val segments = graph.edges.associate { edge -> edge.id to edgeScreenPoints(edge, graph, view).zipWithNext() }
    return FlowHitTesting.hitEdge(point, segments, tolerance = 12.0)
}

private fun edgeScreenPoints(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): List<FlowPoint> {
    val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return emptyList()
    val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return emptyList()
    val sourceSize = source.size ?: FlowSize(160.0, 72.0)
    val targetSize = target.size ?: FlowSize(160.0, 72.0)
    val points = listOf(FlowPoint(source.position.x + sourceSize.width / 2, source.position.y + sourceSize.height)) + view.edgeViews.firstOrNull { it.edgeId == edge.id }?.bendPoints.orEmpty() + FlowPoint(target.position.x + targetSize.width / 2, target.position.y)
    return points.map { FlowViewportTransform.graphToScreen(it, view.viewport) }
}
