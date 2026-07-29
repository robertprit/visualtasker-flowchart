/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.visualtasker.flowchart.compose

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*
import kotlin.math.max
import kotlin.math.min

internal val FlowchartToolbarTouchTargetDp = 48.dp

@Composable
public fun FlowchartHost(
    graphDocument: FlowGraphDocument,
    viewDocument: FlowViewDocument?,
    runtimeSnapshot: FlowRuntimeSnapshot?,
    controller: FlowchartController,
    uiConfig: FlowchartUiConfig = FlowchartUiConfig(),
    callbacks: FlowchartHostCallbacks = FlowchartHostCallbacks(),
    nodeShapeProvider: FlowchartNodeShapeProvider? = null,
) {
    var controllerState by remember(controller) { mutableStateOf(controller.snapshot()) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var gridVisible by remember(graphDocument.documentId) { mutableStateOf(uiConfig.gridEnabled) }
    DisposableEffect(controller, callbacks) {
        controller.setListeners(
            { callbacks.onViewDocumentChanged(it); controllerState = controller.snapshot() },
            { callbacks.onStatusMessage(it); controllerState = controller.snapshot() },
        )
        onDispose { controller.setListeners(null, null) }
    }
    LaunchedEffect(controller, graphDocument, viewDocument) { controller.attachGraph(graphDocument, viewDocument); controllerState = controller.snapshot() }
    LaunchedEffect(controller, graphDocument.documentId, graphDocument.documentRevision, hostSize) {
        val current = controller.snapshot().view ?: return@LaunchedEffect
        val fitted = fitFlowViewToViewport(current, hostSize)
        if (fitted != current) {
            controller.attachGraph(graphDocument, fitted)
            controllerState = controller.snapshot()
        }
    }
    LaunchedEffect(controller, graphDocument.documentId, hostSize, controllerState.view?.viewport) {
        val current = controller.snapshot().view ?: return@LaunchedEffect
        if (hostSize.width > 0 && hostSize.height > 0 && !flowViewHasVisibleNode(current, hostSize)) {
            controller.attachGraph(graphDocument, fitFlowViewToViewport(current, hostSize))
            controllerState = controller.snapshot()
        }
    }
    LaunchedEffect(controller, runtimeSnapshot) { runtimeSnapshot?.let(controller::attachRuntime); controllerState = controller.snapshot() }
    val view = controllerState.view
    if (graphDocument.nodes.isEmpty() || view == null) {
        Box(Modifier.fillMaxSize().testTag("flowchart-empty").semantics { contentDescription = "Empty flowchart" }) { Text("No flowchart nodes", Modifier.padding(24.dp)) }
        return
    }
    Box(Modifier.fillMaxSize().onSizeChanged { hostSize = it }.background(uiConfig.colorTokens.background)) {
        FlowCanvas(graphDocument, view, controllerState.runtime, controllerState.interaction, uiConfig, nodeShapeProvider, gridVisible)
        FlowLabelsAndSemantics(graphDocument, view, controllerState, callbacks)
        FlowGestureLayer(graphDocument, view, controller, uiConfig, callbacks) { controllerState = controller.snapshot() }
        FlowchartIconBar(
            controller = controller,
            config = uiConfig,
            gridVisible = gridVisible,
            onToggleGrid = { gridVisible = !gridVisible },
        ) { controllerState = controller.snapshot() }
    }
}

internal fun fitFlowViewToViewport(
    view: FlowViewDocument,
    hostSize: IntSize,
    marginPx: Double = 56.0,
    minZoom: Double = 0.25,
    maxZoom: Double = 1.25,
): FlowViewDocument {
    if (hostSize.width <= 0 || hostSize.height <= 0 || view.nodeViews.isEmpty()) return view
    val bounds = view.nodeViews.map { node ->
        val size = node.size ?: FlowSize(160.0, 72.0)
        FlowRect(node.position, size)
    }
    val left = bounds.minOf { it.left }
    val top = bounds.minOf { it.top }
    val right = bounds.maxOf { it.right }
    val bottom = bounds.maxOf { it.bottom }
    val contentWidth = max(1.0, right - left)
    val contentHeight = max(1.0, bottom - top)
    val availableWidth = max(1.0, hostSize.width.toDouble() - marginPx * 2)
    val availableHeight = max(1.0, hostSize.height.toDouble() - marginPx * 2)
    val zoom = min(availableWidth / contentWidth, availableHeight / contentHeight)
        .coerceIn(minZoom, maxZoom)
    val pan = FlowPoint(
        x = (hostSize.width - contentWidth * zoom) / 2.0 - left * zoom,
        y = (hostSize.height - contentHeight * zoom) / 2.0 - top * zoom,
    )
    if (!pan.x.isFinite() || !pan.y.isFinite() || !zoom.isFinite()) return view
    val fitted = view.copy(viewport = FlowViewport(pan = pan, zoom = zoom))
    return if (fitted.viewport == view.viewport) view else fitted
}

internal fun flowViewHasVisibleNode(
    view: FlowViewDocument,
    hostSize: IntSize,
    marginPx: Double = 24.0,
): Boolean {
    if (hostSize.width <= 0 || hostSize.height <= 0) return true
    return view.nodeViews.any { node ->
        val size = node.size ?: FlowSize(160.0, 72.0)
        val left = node.position.x * view.viewport.zoom + view.viewport.pan.x
        val top = node.position.y * view.viewport.zoom + view.viewport.pan.y
        val right = (node.position.x + size.width) * view.viewport.zoom + view.viewport.pan.x
        val bottom = (node.position.y + size.height) * view.viewport.zoom + view.viewport.pan.y
        right >= marginPx &&
            bottom >= marginPx &&
            left <= hostSize.width - marginPx &&
            top <= hostSize.height - marginPx
    }
}

@Composable
private fun FlowCanvas(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    runtime: FlowRuntimeSnapshot?,
    interaction: FlowInteractionState,
    config: FlowchartUiConfig,
    nodeShapeProvider: FlowchartNodeShapeProvider?,
    gridVisible: Boolean,
) {
    Canvas(Modifier.fillMaxSize().testTag("flowchart-canvas")) {
        val viewport = view.viewport
        if (gridVisible) {
            drawFlowchartDotGrid(viewport, config)
        }
        fun screen(point: FlowPoint) = Offset((point.x * viewport.zoom + viewport.pan.x).toFloat(), (point.y * viewport.zoom + viewport.pan.y).toFloat())
        graph.edges.sortedBy { it.id.value }.forEach { edge ->
            val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return@forEach
            val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return@forEach
            val sourceSize = source.size ?: FlowSize(160.0, 72.0); val targetSize = target.size ?: FlowSize(160.0, 72.0)
            val start = FlowPoint(source.position.x + sourceSize.width / 2, source.position.y + sourceSize.height)
            val end = FlowPoint(target.position.x + targetSize.width / 2, target.position.y)
            val bends = view.edgeViews.firstOrNull { it.edgeId == edge.id }?.bendPoints.orEmpty()
            val points = (listOf(start) + bends + end).map(::screen)
            val edgeColor = when (flowEdgeVisualCategory(edge.kind)) {
                FlowchartEdgeVisualCategory.DEFAULT -> config.colorTokens.edge
                FlowchartEdgeVisualCategory.BRANCH -> config.colorTokens.branchEdge
                FlowchartEdgeVisualCategory.LOOP -> config.colorTokens.loopEdge
                FlowchartEdgeVisualCategory.ERROR -> config.colorTokens.errorEdge
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
            val edgePresentation = flowEdgePresentation(
                points = points,
                arrowLength = config.shapeTokens.arrowLengthDp.dp.toPx().toDouble(),
                arrowWidth = config.shapeTokens.arrowWidthDp.dp.toPx().toDouble(),
            )
            edgePresentation.connector?.let { startPoint ->
                drawCircle(edgeColor, config.shapeTokens.connectorRadiusDp.dp.toPx(), startPoint)
            }
            val arrow = edgePresentation.arrowHead
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
            val fill = flowNodeFill(node, config)
            val visualPath = resolveNodeShape(nodeShapeProvider, node, canvasSize.width, canvasSize.height)
            if (visualPath != null) {
                translate(origin.x, origin.y) {
                    drawPath(visualPath, fill)
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
                drawRoundRect(fill, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()))
                drawRoundRect(stroke, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()), style = Stroke(config.shapeTokens.nodeStrokeWidthDp.dp.toPx(), pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            }
            if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) drawCircle(config.colorTokens.diagnostic, 6.dp.toPx(), Offset(origin.x + canvasSize.width - 10.dp.toPx(), origin.y + 10.dp.toPx()))
        }
    }
}

internal fun flowNodeFill(
    node: FlowGraphNode,
    config: FlowchartUiConfig,
): androidx.compose.ui.graphics.Color = when (node.kind.standard) {
    FlowNodeKind.ENTRY,
    FlowNodeKind.EXIT -> config.colorTokens.triggerNodeFill

    FlowNodeKind.DECISION,
    FlowNodeKind.ELSE_IF,
    FlowNodeKind.ELSE,
    FlowNodeKind.LOOP_START,
    FlowNodeKind.LOOP_END,
    FlowNodeKind.TRY_START,
    FlowNodeKind.CATCH,
    FlowNodeKind.TRY_END -> config.colorTokens.decisionNodeFill

    FlowNodeKind.INPUT,
    FlowNodeKind.OUTPUT -> config.colorTokens.ioNodeFill

    FlowNodeKind.UNKNOWN_SOURCE,
    null -> config.colorTokens.unknownNodeFill

    else -> config.colorTokens.actionNodeFill
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlowchartDotGrid(
    viewport: FlowViewport,
    config: FlowchartUiConfig,
) {
    val baseSpacing = 24.dp.toPx()
    val spacing = (baseSpacing * viewport.zoom.toFloat()).coerceIn(12.dp.toPx(), 48.dp.toPx())
    val radius = 1.25.dp.toPx()
    val startX = positiveModulo(viewport.pan.x.toFloat(), spacing)
    val startY = positiveModulo(viewport.pan.y.toFloat(), spacing)
    var x = startX
    while (x <= size.width) {
        var y = startY
        while (y <= size.height) {
            drawCircle(config.colorTokens.gridDot, radius, Offset(x, y))
            y += spacing
        }
        x += spacing
    }
}

private fun positiveModulo(value: Float, mod: Float): Float {
    if (mod <= 0f || !value.isFinite() || !mod.isFinite()) return 0f
    val remainder = value % mod
    return if (remainder < 0f) remainder + mod else remainder
}

internal fun resolveNodeShape(
    provider: FlowchartNodeShapeProvider?,
    node: FlowGraphNode,
    width: Float,
    height: Float,
): Path? = provider?.pathFor(node, width, height)

internal enum class FlowchartEdgeVisualCategory {
    DEFAULT,
    BRANCH,
    LOOP,
    ERROR,
}

internal fun flowEdgeVisualCategory(kind: FlowEdgeKind): FlowchartEdgeVisualCategory = when (kind) {
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.FALSE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH -> FlowchartEdgeVisualCategory.BRANCH

    FlowEdgeKind.LOOP_BODY,
    FlowEdgeKind.LOOP_BACK,
    FlowEdgeKind.LOOP_EXIT -> FlowchartEdgeVisualCategory.LOOP

    FlowEdgeKind.ERROR,
    FlowEdgeKind.CATCH_BODY -> FlowchartEdgeVisualCategory.ERROR

    FlowEdgeKind.SEQUENCE,
    FlowEdgeKind.TRY_BODY,
    FlowEdgeKind.FUNCTION_CALL,
    FlowEdgeKind.FUNCTION_RETURN,
    FlowEdgeKind.EVENT,
    FlowEdgeKind.GOTO -> FlowchartEdgeVisualCategory.DEFAULT
}

internal data class FlowchartEdgePresentation(
    val connector: Offset?,
    val arrowHead: List<FlowPoint>,
)

internal fun flowEdgePresentation(
    points: List<Offset>,
    arrowLength: Double,
    arrowWidth: Double,
): FlowchartEdgePresentation = FlowchartEdgePresentation(
    connector = points.firstOrNull(),
    arrowHead = flowArrowHead(points, arrowLength, arrowWidth),
)

internal fun flowArrowHead(
    points: List<Offset>,
    length: Double,
    width: Double,
): List<FlowPoint> {
    if (points.size < 2 || !length.isFinite() || !width.isFinite() || length <= 0.0 || width <= 0.0) {
        return emptyList()
    }
    val tip = points.last()
    var previousIndex = points.lastIndex - 1
    while (previousIndex >= 0 && points[previousIndex] == tip) previousIndex--
    if (previousIndex < 0) return emptyList()
    val previous = points[previousIndex]
    val dx = (tip.x - previous.x).toDouble()
    val dy = (tip.y - previous.y).toDouble()
    val magnitude = kotlin.math.hypot(dx, dy)
    if (!magnitude.isFinite() || magnitude <= 0.0) return emptyList()
    val unitX = dx / magnitude
    val unitY = dy / magnitude
    val effectiveLength = minOf(length, magnitude)
    val baseX = tip.x - unitX * effectiveLength
    val baseY = tip.y - unitY * effectiveLength
    val halfWidth = minOf(width / 2.0, effectiveLength / 2.0)
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
    val platformView = LocalView.current
    val haptic = LocalHapticFeedback.current
    val currentView by rememberUpdatedState(view)
    var previousTapAt by remember { mutableLongStateOf(0L) }
    var previousTapPosition by remember { mutableStateOf<Offset?>(null) }
    val modifier = Modifier.fillMaxSize().testTag("flowchart-gestures")
        .pointerInput(graph, config.panEnabled, config.zoomEnabled) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                if (!config.panEnabled && !config.zoomEnabled) return@detectTransformGestures
                val current = controller.snapshot().view ?: return@detectTransformGestures
                val old = current.viewport
                val nextZoom = if (config.zoomEnabled) {
                    (old.zoom * zoom.toDouble()).coerceIn(0.1, 8.0)
                } else {
                    old.zoom
                }
                val anchor = FlowPoint(centroid.x.toDouble(), centroid.y.toDouble())
                val graphAnchor = FlowViewportTransform.screenToGraph(anchor, old)
                val panX = if (config.zoomEnabled) anchor.x - graphAnchor.x * nextZoom else old.pan.x
                val panY = if (config.zoomEnabled) anchor.y - graphAnchor.y * nextZoom else old.pan.y
                val nextPan = if (config.panEnabled) {
                    FlowPoint(panX + pan.x.toDouble(), panY + pan.y.toDouble())
                } else {
                    FlowPoint(panX, panY)
                }
                controller.replaceViewport(FlowViewport(nextPan, nextZoom))
                refresh()
            }
        }
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
                        if (dragNode != null) {
                            controller.dispatch(FlowInteractionAction.CommitNodeDrag)
                            playEditorSound(platformView, config.soundEffectsEnabled)
                            if (config.hapticFeedbackEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        } else if (panning) controller.dispatch(FlowInteractionAction.CommitViewportPan)
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

@Composable
private fun FlowchartIconBar(
    controller: FlowchartController,
    config: FlowchartUiConfig,
    gridVisible: Boolean,
    onToggleGrid: () -> Unit,
    refresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowchartToolbarIconButton(
                description = config.accessibilityLabels.undoView,
                icon = Icons.AutoMirrored.Filled.Undo,
                onClick = { controller.dispatch(FlowInteractionAction.UndoViewChange); refresh() },
            )
            FlowchartToolbarIconButton(
                description = config.accessibilityLabels.redoView,
                icon = Icons.AutoMirrored.Filled.Redo,
                onClick = { controller.dispatch(FlowInteractionAction.RedoViewChange); refresh() },
            )
            if (config.zoomEnabled) {
                FlowchartToolbarIconButton(
                    description = config.accessibilityLabels.zoomOut,
                    icon = Icons.Filled.ZoomOut,
                    onClick = { controller.dispatch(FlowInteractionAction.ZoomViewport(1 / 1.2, FlowPoint(0.0, 0.0))); refresh() },
                )
                FlowchartToolbarIconButton(
                    description = config.accessibilityLabels.zoomIn,
                    icon = Icons.Filled.ZoomIn,
                    onClick = { controller.dispatch(FlowInteractionAction.ZoomViewport(1.2, FlowPoint(0.0, 0.0))); refresh() },
                )
            }
            FlowchartToolbarIconButton(
                description = config.accessibilityLabels.centerView,
                icon = Icons.Filled.CenterFocusStrong,
                onClick = { controller.attachGraph(controller.snapshot().graph ?: return@FlowchartToolbarIconButton, null); refresh() },
            )
            FlowchartToolbarIconButton(
                description = config.accessibilityLabels.toggleGrid,
                icon = Icons.Filled.GridOn,
                selected = gridVisible,
                onClick = onToggleGrid,
            )
        }
    }
}

@Composable
private fun FlowchartToolbarIconButton(
    description: String,
    icon: ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(FlowchartToolbarTouchTargetDp).semantics {
                contentDescription = description
                this.selected = selected
            },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(icon, contentDescription = null)
        }
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

private fun playEditorSound(
    platformView: android.view.View,
    enabled: Boolean,
) {
    if (!enabled) return
    platformView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 32)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
        platformView.postDelayed({ tone.release() }, 80L)
    }
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
