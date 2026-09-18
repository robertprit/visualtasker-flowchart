/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val FlowNodePlacementAnimationMillis = 180

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
    DisposableEffect(controller, callbacks) {
        controller.setListeners(
            { callbacks.onViewDocumentChanged(it); controllerState = controller.snapshot() },
            { callbacks.onStatusMessage(it); controllerState = controller.snapshot() },
            { controllerState = it },
        )
        onDispose { controller.setListeners(null, null) }
    }
    LaunchedEffect(controller, graphDocument) {
        controller.attachGraph(graphDocument, viewDocument)
        controllerState = controller.snapshot()
    }
    LaunchedEffect(controller, runtimeSnapshot) { runtimeSnapshot?.let(controller::attachRuntime); controllerState = controller.snapshot() }
    val view = controllerState.view
    if (graphDocument.nodes.isEmpty() || view == null) {
        Box(Modifier.fillMaxSize().testTag("flowchart-empty").semantics { contentDescription = "Empty flowchart" }) { Text("No flowchart nodes", Modifier.padding(24.dp)) }
        return
    }
    Box(Modifier.fillMaxSize().background(uiConfig.colorTokens.background)) {
        val collapsedFacetNodeIds = view.collapsedFacetNodeIds()
        var lockedFacetNodeIds by remember(controller, graphDocument.documentRevision) { mutableStateOf<Set<FlowNodeId>>(emptySet()) }
        FlowCanvas(graphDocument, view, controllerState.runtime, controllerState.interaction, uiConfig, nodeShapeProvider, collapsedFacetNodeIds, lockedFacetNodeIds)
        FlowLabelsAndSemantics(graphDocument, view, controllerState, uiConfig, callbacks, collapsedFacetNodeIds)
        FlowGestureLayer(
            graph = graphDocument,
            view = view,
            controller = controller,
            config = uiConfig,
            callbacks = callbacks,
            collapsedFacetNodeIds = collapsedFacetNodeIds,
            lockedFacetNodeIds = lockedFacetNodeIds,
            onToggleFacetCollapsed = { facetId ->
                controller.dispatch(
                    FlowInteractionAction.SetFacetCollapsed(
                        facetId = facetId,
                        collapsed = facetId !in collapsedFacetNodeIds,
                    ),
                )
                controllerState = controller.snapshot()
            },
            onToggleFacetLocked = { facetId ->
                lockedFacetNodeIds = if (facetId in lockedFacetNodeIds) {
                    lockedFacetNodeIds - facetId
                } else {
                    lockedFacetNodeIds + facetId
                }
            },
        ) { controllerState = controller.snapshot() }
        if (uiConfig.controlsEnabled) {
            ZoomControls(controller, uiConfig) { controllerState = controller.snapshot() }
        }
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
    collapsedFacetNodeIds: Set<FlowNodeId> = emptySet(),
    lockedFacetNodeIds: Set<FlowNodeId> = emptySet(),
) {
    val placementAnimations = remember { mutableStateMapOf<FlowNodeId, Animatable<Offset, androidx.compose.animation.core.AnimationVector2D>>() }
    val nodePositionTargets = remember(view.nodeViews) {
        view.nodeViews.associate { nodeView ->
            nodeView.nodeId to Offset(nodeView.position.x.toFloat(), nodeView.position.y.toFloat())
        }
    }
    val activeDragNodeIds = interaction.dragState?.nodeIds.orEmpty()
    LaunchedEffect(nodePositionTargets, activeDragNodeIds) {
        placementAnimations.keys
            .filterNot { it in nodePositionTargets }
            .forEach(placementAnimations::remove)
        nodePositionTargets.forEach { (nodeId, target) ->
            val animation = placementAnimations.getOrPut(nodeId) {
                Animatable(target, Offset.VectorConverter)
            }
            if (nodeId in activeDragNodeIds) {
                animation.snapTo(target)
            } else if ((animation.value - target).getDistance() > 0.5f) {
                animation.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = FlowNodePlacementAnimationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }
    val renderNodeViews = view.nodeViews.map { nodeView ->
        val animated = placementAnimations[nodeView.nodeId]?.value
        if (animated == null || nodeView.nodeId in activeDragNodeIds) {
            nodeView
        } else {
            nodeView.copy(position = FlowPoint(animated.x.toDouble(), animated.y.toDouble()))
        }
    }
    val renderView = view.copy(nodeViews = renderNodeViews)
    Canvas(Modifier.fillMaxSize().testTag("flowchart-canvas")) {
        val viewport = renderView.viewport
        val visibleRuntime = runtime.takeIf { config.runtimeOverlayEnabled }
        val executionKind = visibleRuntime?.executionKindOrNull() ?: graph.executionKind()
        fun screen(point: FlowPoint) = Offset((point.x * viewport.zoom + viewport.pan.x).toFloat(), (point.y * viewport.zoom + viewport.pan.y).toFloat())
        val collapsedNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
        drawBackgroundFacetRegions(graph, renderView, config, collapsedFacetNodeIds, size, ::screen)
        val edgeRoutes = flowchartVisibleEdges(graph.edges, config)
            .sortedBy { it.id.value }
            .filterNot { edge -> edge.sourceNodeId in collapsedNodeIds || edge.targetNodeId in collapsedNodeIds }
            .mapNotNull { edge ->
                val points = edgeGraphPoints(edge, graph, renderView).map(::screen)
                if (points.size < 2) null else Triple(edge, points, edgeIsWrapCable(edge, graph, renderView))
            }
            .sortedWith(compareBy<Triple<FlowGraphEdge, List<Offset>, Boolean>> { it.first.isSelectedOrOutgoingFromSelection(interaction) }.thenBy { it.first.id.value })
        edgeRoutes.forEachIndexed { edgeIndex, (edge, points, isWrapCable) ->
            if (points.size < 2) return@forEachIndexed
            val traversed = edge.id in visibleRuntime?.traversedEdgeIds.orEmpty()
            val highlighted = edge.isSelectedOrOutgoingFromSelection(interaction)
            val baseEdgeColor = when {
                highlighted -> config.colorTokens.selectedStroke
                traversed -> config.colorTokens.traversedEdge
                else -> when (flowEdgeVisualCategory(edge.kind)) {
                    FlowchartEdgeVisualCategory.DEFAULT -> config.colorTokens.edge
                    FlowchartEdgeVisualCategory.BRANCH -> config.colorTokens.branchEdge
                    FlowchartEdgeVisualCategory.DATA -> config.colorTokens.dataEdge
                    FlowchartEdgeVisualCategory.LOOP -> config.colorTokens.loopEdge
                    FlowchartEdgeVisualCategory.ERROR -> config.colorTokens.errorEdge
                }
            }
            val groupAlpha = if (highlighted || traversed) 1f else minOf(
                flowNodeGroupAlpha(edge.sourceNodeId, graph),
                flowNodeGroupAlpha(edge.targetNodeId, graph),
            )
            val edgeColor = baseEdgeColor.copy(alpha = baseEdgeColor.alpha * groupAlpha)
            val edgeStrokeWidth = config.shapeTokens.edgeStrokeWidthDp.dp.toPx() * when {
                highlighted -> 2.05f
                traversed -> 1.55f
                else -> 1f
            }
            val bridges = edgeBridgeIntersections(
                points = points,
                previousRoutes = edgeRoutes.take(edgeIndex).map { it.second },
                minDistanceFromEnds = edgeStrokeWidth * 4f,
            )
            if (isWrapCable && points.size >= 4) {
                drawPath(
                    path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        cubicTo(
                            points[1].x,
                            points[1].y,
                            points[2].x,
                            points[2].y,
                            points.last().x,
                            points.last().y,
                        )
                    },
                    color = edgeColor,
                    style = Stroke(width = edgeStrokeWidth * 0.86f, cap = StrokeCap.Round),
                )
            } else {
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        color = edgeColor,
                        start = a,
                        end = b,
                        strokeWidth = edgeStrokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
            bridges.forEach { bridge ->
                drawCircle(
                    color = config.colorTokens.background.copy(alpha = 0.96f),
                    radius = edgeStrokeWidth * 1.95f,
                    center = bridge.center,
                )
                drawArc(
                    color = edgeColor,
                    startAngle = bridge.startAngle,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(
                        bridge.center.x - edgeStrokeWidth * 2.0f,
                        bridge.center.y - edgeStrokeWidth * 2.0f,
                    ),
                    size = Size(edgeStrokeWidth * 4.0f, edgeStrokeWidth * 4.0f),
                    style = Stroke(width = edgeStrokeWidth, cap = StrokeCap.Round),
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
            if (node.isBackgroundFacetNode()) return@forEach
            if (node.id in collapsedNodeIds) return@forEach
            val nodeView = renderView.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowNodeViewDefaults.StandardSize
            val origin = screen(nodeView.position)
            val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            drawNodePorts(
                node = node,
                origin = origin,
                size = canvasSize,
                zoom = viewport.zoom,
                config = config,
            )
        }
        graph.nodes.sortedBy { it.id.value }.forEach { node ->
            if (node.isBackgroundFacetNode()) return@forEach
            if (node.id in collapsedNodeIds) return@forEach
            val nodeView = renderView.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowNodeViewDefaults.StandardSize; val origin = screen(nodeView.position); val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            val runtimeState = visibleRuntime?.nodeStates?.get(node.id)
            val selected = node.id in interaction.selectedNodeIds
            val runtimeActive = visibleRuntime?.activeNodeId == node.id ||
                runtimeState in setOf(FlowRuntimeNodeState.RUNNING, FlowRuntimeNodeState.WAITING, FlowRuntimeNodeState.FAILED)
            val emphasized = selected || runtimeActive
            val groupAlpha = if (emphasized) 1f else flowNodeGroupAlpha(node.id, graph)
            val baseNodeFillColor = flowNodeFillColor(node, config.colorTokens, executionKind)
            val nodeFillColor = baseNodeFillColor.copy(alpha = baseNodeFillColor.alpha * groupAlpha)
            val baseStroke = when {
                selected -> config.colorTokens.selectedStroke
                runtimeState == FlowRuntimeNodeState.FAILED -> config.colorTokens.failedStroke
                visibleRuntime?.activeNodeId == node.id -> config.colorTokens.runningStroke
                runtimeState in setOf(FlowRuntimeNodeState.RUNNING, FlowRuntimeNodeState.WAITING) -> config.colorTokens.runningStroke
                runtimeState == FlowRuntimeNodeState.SUCCEEDED -> config.colorTokens.succeededStroke
                runtimeState == FlowRuntimeNodeState.SKIPPED -> config.colorTokens.skippedStroke
                else -> config.colorTokens.nodeStroke
            }
            val stroke = baseStroke.copy(alpha = baseStroke.alpha * groupAlpha)
            val emphasisColor = when {
                selected -> config.colorTokens.selectedStroke
                runtimeState == FlowRuntimeNodeState.FAILED -> config.colorTokens.failedStroke
                runtimeActive -> config.colorTokens.runningStroke
                else -> null
            }
            val regularStrokeWidth = config.shapeTokens.nodeStrokeWidthDp.dp.toPx()
            val emphasisStrokeWidth = regularStrokeWidth * if (selected) 3.8f else 2.35f
            val haloStrokeWidth = regularStrokeWidth * if (selected) 8.2f else 5.2f
            val presentationNode = if (node.effectiveTerminatorRole() != null) {
                node.copy(
                    properties = node.properties +
                        (FlowLifecycleSemantics.EXECUTION_KIND_PROPERTY to FlowSemanticValue.StringValue(executionKind.wireValue)),
                )
            } else {
                node
            }
            val visualPath = resolveNodeShape(nodeShapeProvider, presentationNode, canvasSize.width, canvasSize.height)
            if (visualPath != null) {
                translate(origin.x, origin.y) {
                    emphasisColor?.let { color ->
                        drawPath(
                            path = visualPath,
                            color = color.copy(alpha = if (selected) 0.48f else 0.32f),
                            style = Stroke(width = haloStrokeWidth, cap = StrokeCap.Round),
                        )
                    }
                    drawPath(visualPath, nodeFillColor)
                    drawPath(
                        path = visualPath,
                        color = stroke,
                        style = Stroke(
                            width = if (emphasisColor != null) emphasisStrokeWidth else regularStrokeWidth,
                            pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) {
                                PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                            } else {
                                null
                            },
                        ),
                    )
                }
            } else {
                emphasisColor?.let { color ->
                    drawRoundRect(
                        color = color.copy(alpha = if (selected) 0.48f else 0.32f),
                        topLeft = origin,
                        size = canvasSize,
                        cornerRadius = CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()),
                        style = Stroke(width = haloStrokeWidth),
                    )
                }
                drawRoundRect(nodeFillColor, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()))
                drawRoundRect(stroke, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()), style = Stroke(if (emphasisColor != null) emphasisStrokeWidth else regularStrokeWidth, pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            }
            if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) drawCircle(config.colorTokens.diagnostic, 6.dp.toPx(), Offset(origin.x + canvasSize.width - 10.dp.toPx(), origin.y + 10.dp.toPx()))
        }
        if (config.facetHandlesVisible && interaction.facetHandlesVisible) {
            drawFacetHandles(
                graph = graph,
                view = view,
                config = config,
                collapsedFacetNodeIds = collapsedFacetNodeIds,
                lockedFacetNodeIds = lockedFacetNodeIds,
                viewportSize = size,
                screen = ::screen,
            )
        }
    }
}

private fun FlowGraphEdge.isSelectedOrOutgoingFromSelection(interaction: FlowInteractionState): Boolean =
    id in interaction.selectedEdgeIds || sourceNodeId in interaction.selectedNodeIds

internal enum class FlowchartNodeDetailLevel {
    Full,
    Compact,
    Dot,
}

internal fun flowNodeDetailLevel(
    node: FlowGraphNode,
    zoom: Double,
    screenWidth: Double = Double.POSITIVE_INFINITY,
    screenHeight: Double = Double.POSITIVE_INFINITY,
): FlowchartNodeDetailLevel {
    if (screenWidth < 36.0 || screenHeight < 24.0) return FlowchartNodeDetailLevel.Dot
    if (screenWidth < 96.0 || screenHeight < 42.0) return FlowchartNodeDetailLevel.Compact
    if (zoom < 0.22) return FlowchartNodeDetailLevel.Dot
    val auxiliary = node.isAuxiliaryVisualNode()
    return when {
        auxiliary && zoom < 0.82 -> FlowchartNodeDetailLevel.Compact
        !auxiliary && zoom < 0.42 -> FlowchartNodeDetailLevel.Compact
        else -> FlowchartNodeDetailLevel.Full
    }
}

internal fun flowNodeGroupAlpha(nodeId: FlowNodeId, graph: FlowGraphDocument): Float {
    val activeGroups = graph.nodes.filter { node ->
        node.properties["visualFacet"] == FlowSemanticValue.BooleanValue(true) &&
            node.properties["remFlowKind"] == FlowSemanticValue.StringValue("GROUP") &&
            node.properties["remFlow.active"] == FlowSemanticValue.BooleanValue(true)
    }
    if (activeGroups.isEmpty()) return 1f
    val activeNodeIds = activeGroups.flatMapTo(linkedSetOf()) { group ->
        buildList {
            (group.properties["nodeIds"] as? FlowSemanticValue.ListValue)
                ?.values
                .orEmpty()
                .mapNotNullTo(this) { (it as? FlowSemanticValue.StringValue)?.value?.let(::FlowNodeId) }
            (group.properties["ownerNodeId"] as? FlowSemanticValue.StringValue)?.value?.let { add(FlowNodeId(it)) }
        }
    }
    return if (nodeId in activeNodeIds) 1f else 0.28f
}

internal fun flowNodeCompactLabel(node: FlowGraphNode): String {
    val blockType = (node.properties["blockType"] as? FlowSemanticValue.StringValue)?.value.orEmpty()
    val explicit = (node.properties["shortLabel"] as? FlowSemanticValue.StringValue)?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (explicit != null) return explicit.take(6)
    return when {
        blockType.startsWith("logic.") -> "LOG"
        blockType.startsWith("literal.") -> "LIT"
        blockType.startsWith("variable.") || blockType.startsWith("variables.") -> "VAR"
        node.kind.standard == FlowNodeKind.ASSIGNMENT -> "SET"
        node.kind.standard == FlowNodeKind.PROPERTY_ACCESS -> "GET"
        node.kind.standard == FlowNodeKind.INPUT -> "IN"
        node.kind.standard == FlowNodeKind.OUTPUT -> "OUT"
        node.kind.standard == FlowNodeKind.DECISION -> "IF"
        node.kind.standard == FlowNodeKind.LOOP_START -> "LOOP"
        else -> node.label.trim().ifEmpty { "NODE" }.take(6)
    }
}

private fun FlowGraphNode.isAuxiliaryVisualNode(): Boolean {
    val blockType = (properties["blockType"] as? FlowSemanticValue.StringValue)?.value.orEmpty()
    return kind.standard in setOf(
        FlowNodeKind.ASSIGNMENT,
        FlowNodeKind.PROPERTY_ACCESS,
        FlowNodeKind.INPUT,
        FlowNodeKind.OUTPUT,
    ) ||
        blockType.startsWith("logic.") ||
        blockType.startsWith("literal.") ||
        blockType.startsWith("variable.") ||
        blockType.startsWith("variables.")
}

private fun DrawScope.drawBackgroundFacetRegions(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    config: FlowchartUiConfig,
    collapsedFacetNodeIds: Set<FlowNodeId>,
    viewportSize: Size,
    screen: (FlowPoint) -> Offset,
) {
    val hiddenFacetIds = collapsedFacetVisibility(graph, collapsedFacetNodeIds).hiddenFacetIds
    flowFacetRegions(graph, view, viewportSize, density, screen)
        .filterNot { it.facet.id in hiddenFacetIds }
        .filter { region -> view.viewport.zoom >= 0.72 || region.facet.id in collapsedFacetNodeIds }
        .forEach { region ->
            val kind = (region.facet.properties["facetKind"] as? FlowSemanticValue.StringValue)?.value.orEmpty()
            val color = when (kind) {
                "VARIABLE_BULK" -> config.colorTokens.variableNodeFill
                "COLLAPSE_GROUP" -> config.colorTokens.feedbackNodeFill
                else -> config.colorTokens.branchEdge
            }
            drawRoundRect(
                color = color.copy(alpha = if (region.facet.id in collapsedFacetNodeIds) 0.10f else 0.035f),
                topLeft = region.bounds.topLeft,
                size = region.bounds.size,
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            )
            drawRoundRect(
                color = color.copy(alpha = if (region.facet.id in collapsedFacetNodeIds) 0.44f else 0.22f),
                topLeft = region.bounds.topLeft,
                size = region.bounds.size,
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
            )
            if (kind == "VARIABLE_BULK") {
                drawVariableBulkPreview(region, color, collapsed = region.facet.id in collapsedFacetNodeIds)
            }
        }
}

private fun DrawScope.drawFacetHandles(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    config: FlowchartUiConfig,
    collapsedFacetNodeIds: Set<FlowNodeId>,
    lockedFacetNodeIds: Set<FlowNodeId>,
    viewportSize: Size,
    screen: (FlowPoint) -> Offset,
) {
    val hiddenFacetIds = collapsedFacetVisibility(graph, collapsedFacetNodeIds).hiddenFacetIds
    flowFacetRegions(graph, view, viewportSize, density, screen)
        .filterNot { it.facet.id in hiddenFacetIds }
        .forEach { region ->
        val kind = (region.facet.properties["facetKind"] as? FlowSemanticValue.StringValue)?.value.orEmpty()
        val color = when (kind) {
            "VARIABLE_BULK" -> config.colorTokens.variableNodeFill
            "COLLAPSE_GROUP" -> config.colorTokens.feedbackNodeFill
            else -> config.colorTokens.branchEdge
        }
        drawFacetHandle(
            region = region,
            color = color,
            locked = region.facet.id in lockedFacetNodeIds,
            collapsed = region.facet.id in collapsedFacetNodeIds,
        )
    }
}

private enum class VariableBulkVisualLayout {
    Stack,
    Single,
    GridHorizontal,
    GridVertical,
}

private fun FlowGraphNode.variableBulkVisualLayout(): VariableBulkVisualLayout =
    when ((properties["remFlow.layout"] as? FlowSemanticValue.StringValue)?.value?.trim()?.lowercase()) {
        "single" -> VariableBulkVisualLayout.Single
        "grid", "grid-horizontal", "horizontal-grid" -> VariableBulkVisualLayout.GridHorizontal
        "grid-vertical", "vertical-grid" -> VariableBulkVisualLayout.GridVertical
        else -> VariableBulkVisualLayout.Stack
    }

private fun DrawScope.drawVariableBulkPreview(
    region: FlowFacetRegion,
    color: Color,
    collapsed: Boolean,
) {
    val layout = region.facet.variableBulkVisualLayout()
    if (layout == VariableBulkVisualLayout.GridHorizontal || layout == VariableBulkVisualLayout.GridVertical) {
        drawVariableBulkGrid(region, color, collapsed, layout)
        return
    }
    val cardWidth = minOf(region.bounds.width * 0.62f, 136.dp.toPx())
    val cardHeight = minOf(region.bounds.height * 0.26f, 36.dp.toPx())
    if (cardWidth < 28.dp.toPx() || cardHeight < 14.dp.toPx()) return
    val top = region.bounds.top + 8.dp.toPx()
    val left = region.bounds.right - cardWidth - 10.dp.toPx()
    val stackCount = if (layout == VariableBulkVisualLayout.Single) 1 else minOf(4, region.nodeIds.size.coerceAtLeast(1))
    repeat(stackCount) { index ->
        val offset = (stackCount - 1 - index) * 6.dp.toPx()
        val alpha = if (collapsed) 0.24f else 0.15f + index * 0.035f
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left - offset, top + offset),
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(cardHeight / 2f, cardHeight / 2f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = if (collapsed) 0.28f else 0.18f),
            topLeft = Offset(left - offset, top + offset),
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(cardHeight / 2f, cardHeight / 2f),
            style = Stroke(1.dp.toPx()),
        )
    }
}

private fun DrawScope.drawVariableBulkGrid(
    region: FlowFacetRegion,
    color: Color,
    collapsed: Boolean,
    layout: VariableBulkVisualLayout,
) {
    val cardCount = minOf(9, region.nodeIds.size.coerceAtLeast(1))
    val side = kotlin.math.ceil(kotlin.math.sqrt(cardCount.toDouble())).toInt().coerceAtLeast(1)
    val gap = 4.dp.toPx()
    val cardWidth = minOf(34.dp.toPx(), (region.bounds.width * 0.42f - gap * (side - 1)) / side)
    val cardHeight = minOf(18.dp.toPx(), (region.bounds.height * 0.30f - gap * (side - 1)) / side)
    if (cardWidth < 10.dp.toPx() || cardHeight < 7.dp.toPx()) return
    val gridWidth = side * cardWidth + (side - 1) * gap
    val left = region.bounds.right - gridWidth - 10.dp.toPx()
    val top = region.bounds.top + 8.dp.toPx()
    repeat(cardCount) { index ->
        val column = if (layout == VariableBulkVisualLayout.GridHorizontal) index % side else index / side
        val row = if (layout == VariableBulkVisualLayout.GridHorizontal) index / side else index % side
        val cardTopLeft = Offset(
            x = left + column * (cardWidth + gap),
            y = top + row * (cardHeight + gap),
        )
        drawRoundRect(
            color = color.copy(alpha = if (collapsed) 0.24f else 0.14f + index * 0.012f),
            topLeft = cardTopLeft,
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = if (collapsed) 0.28f else 0.18f),
            topLeft = cardTopLeft,
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }
}

internal data class FlowFacetRegion(
    val facet: FlowGraphNode,
    val nodeIds: Set<FlowNodeId>,
    val bounds: androidx.compose.ui.geometry.Rect,
    val handleBounds: androidx.compose.ui.geometry.Rect,
    val gripBounds: androidx.compose.ui.geometry.Rect,
    val labelBounds: androidx.compose.ui.geometry.Rect,
    val collapseBounds: androidx.compose.ui.geometry.Rect,
    val menuBounds: androidx.compose.ui.geometry.Rect,
    val lockBounds: androidx.compose.ui.geometry.Rect,
)

internal enum class FlowFacetHandleAction {
    Drag,
    Select,
    ToggleCollapse,
    OpenMenu,
    ToggleLock,
}

internal data class FlowFacetHandleHit(
    val region: FlowFacetRegion,
    val action: FlowFacetHandleAction,
)

internal fun flowFacetRegions(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    viewportSize: Size? = null,
    densityScale: Float = 1f,
    screen: (FlowPoint) -> Offset,
): List<FlowFacetRegion> {
    val occupiedNodeBounds = view.nodeViews.map { nodeView ->
        val nodeSize = nodeView.size ?: FlowNodeViewDefaults.StandardSize
        val topLeft = screen(nodeView.position)
        val bottomRight = screen(
            FlowPoint(
                nodeView.position.x + nodeSize.width,
                nodeView.position.y + nodeSize.height,
            ),
        )
        Rect(topLeft, bottomRight)
    }
    val placedHandleBounds = mutableListOf<Rect>()
    return graph.nodes
        .filter { it.isBackgroundFacetNode() }
        .sortedBy { it.id.value }
        .mapNotNull { facet ->
            val nodeIds = facet.facetContentNodeIds()
            val rects = view.nodeViews
                .filter { it.nodeId in nodeIds }
                .map { FlowRect(it.position, it.size ?: FlowNodeViewDefaults.StandardSize) }
            if (rects.isEmpty()) return@mapNotNull null
            val padding = 18f
            val label = facetDisplayLabel(facet)
            val safeDensity = densityScale.coerceIn(1f, 1.8f)
            val handleWidth = ((72f + label.length.coerceAtMost(18) * 4.5f).coerceIn(116f, 180f)) * safeDensity
            val handleHeight = 28f * safeDensity
            val handleGap = 6f * safeDensity
            val origin = screen(FlowPoint(rects.minOf { it.left }, rects.minOf { it.top }))
            val end = screen(FlowPoint(rects.maxOf { it.right }, rects.maxOf { it.bottom }))
            val bounds = androidx.compose.ui.geometry.Rect(
                left = origin.x - padding,
                top = origin.y - padding,
                right = end.x + padding,
                bottom = end.y + padding,
            )
            val handleBounds = facetHandleBounds(
                facetBounds = bounds,
                width = handleWidth,
                height = handleHeight,
                gap = handleGap,
                viewportSize = viewportSize,
                occupiedBounds = occupiedNodeBounds + placedHandleBounds,
            )
            placedHandleBounds += handleBounds
            val actionWidth = 28f * safeDensity
            val gripRight = handleBounds.left + actionWidth
            val menuLeft = handleBounds.right - actionWidth
            val collapseLeft = menuLeft - actionWidth
            FlowFacetRegion(
                facet = facet,
                nodeIds = nodeIds,
                bounds = bounds,
                handleBounds = handleBounds,
                gripBounds = androidx.compose.ui.geometry.Rect(handleBounds.left, handleBounds.top, gripRight, handleBounds.bottom),
                labelBounds = androidx.compose.ui.geometry.Rect(gripRight, handleBounds.top, collapseLeft, handleBounds.bottom),
                collapseBounds = androidx.compose.ui.geometry.Rect(collapseLeft, handleBounds.top, menuLeft, handleBounds.bottom),
                menuBounds = androidx.compose.ui.geometry.Rect(menuLeft, handleBounds.top, handleBounds.right, handleBounds.bottom),
                lockBounds = androidx.compose.ui.geometry.Rect(0f, 0f, 0f, 0f),
            )
        }
}

internal fun facetDisplayLabel(facet: FlowGraphNode): String =
    facet.label.trim().ifEmpty {
        (facet.properties["facetKind"] as? FlowSemanticValue.StringValue)
            ?.value
            ?.lowercase()
            ?.replace('_', ' ')
            ?.replaceFirstChar(Char::uppercase)
            ?: "Facet"
    }

internal fun facetHandleBounds(
    facetBounds: Rect,
    width: Float,
    height: Float,
    gap: Float,
    viewportSize: Size?,
    occupiedBounds: List<Rect> = emptyList(),
): Rect {
    val viewport = viewportSize?.takeIf { it.width > 0f && it.height > 0f }
        ?: return Rect(
            left = facetBounds.left - width - gap,
            top = facetBounds.top + 8f,
            right = facetBounds.left - gap,
            bottom = facetBounds.top + 8f + height,
        )
    val margin = 4f
    val maxLeft = (viewport.width - width - margin).coerceAtLeast(margin)
    val clampedLeft = facetBounds.left.coerceIn(margin, maxLeft)
    val aboveTop = facetBounds.top - gap - height
    val rightLeft = facetBounds.right + gap
    val left = facetBounds.left - gap - width
    val belowTop = facetBounds.bottom + gap
    val horizontalStarts = listOf(
        clampedLeft,
        (facetBounds.center.x - width / 2f).coerceIn(margin, maxLeft),
        (facetBounds.right - width).coerceIn(margin, maxLeft),
    ).distinct()
    val verticalTop = facetBounds.top.coerceIn(margin, (viewport.height - height - margin).coerceAtLeast(margin))
    val candidates = buildList {
        if (aboveTop >= margin) {
            horizontalStarts.forEach { x -> add(Rect(x, aboveTop, x + width, aboveTop + height)) }
        }
        if (rightLeft + width <= viewport.width - margin) {
            add(Rect(rightLeft, verticalTop, rightLeft + width, verticalTop + height))
        }
        if (left >= margin) {
            add(Rect(left, verticalTop, left + width, verticalTop + height))
        }
        if (belowTop + height <= viewport.height - margin) {
            horizontalStarts.forEach { x -> add(Rect(x, belowTop, x + width, belowTop + height)) }
        }
    }
    candidates.minByOrNull { candidate ->
        occupiedBounds.sumOf { occupied -> overlapArea(candidate, occupied).toDouble() } +
            overlapArea(candidate, facetBounds) * 4.0
    }?.let { return it }
    val fallbackTop = facetBounds.top.coerceIn(margin, (viewport.height - height - margin).coerceAtLeast(margin))
    return Rect(clampedLeft, fallbackTop, clampedLeft + width, fallbackTop + height)
}

internal fun overlapArea(first: Rect, second: Rect): Float {
    val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f)
    val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
    return width * height
}

internal fun hitFlowFacetHandle(
    offset: Offset,
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    viewportSize: Size? = null,
    densityScale: Float = 1f,
    collapsedFacetNodeIds: Set<FlowNodeId> = emptySet(),
): FlowFacetHandleHit? {
    fun screen(point: FlowPoint) = FlowViewportTransform.graphToScreen(point, view.viewport)
        .let { Offset(it.x.toFloat(), it.y.toFloat()) }
    val hiddenFacetIds = collapsedFacetVisibility(graph, collapsedFacetNodeIds).hiddenFacetIds
    return flowFacetRegions(graph, view, viewportSize, densityScale, ::screen)
        .filterNot { it.facet.id in hiddenFacetIds }
        .asReversed()
        .firstNotNullOfOrNull { region ->
            when {
                region.gripBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.Drag)
                region.labelBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.Select)
                region.collapseBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.ToggleCollapse)
                region.menuBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.OpenMenu)
                else -> null
            }
        }
}

internal data class EdgeBridge(
    val center: Offset,
    val startAngle: Float,
)

internal fun edgeBridgeIntersections(
    points: List<Offset>,
    previousRoutes: List<List<Offset>>,
    minDistanceFromEnds: Float,
): List<EdgeBridge> =
    buildList {
        points.zipWithNext().forEach { segment ->
            val bridge = segmentBridgeAgainstRoutes(segment.first, segment.second, previousRoutes, minDistanceFromEnds)
            addAll(bridge)
        }
    }.distinctBy { "${it.center.x.toInt()}:${it.center.y.toInt()}:${it.startAngle.toInt()}" }

private fun segmentBridgeAgainstRoutes(
    a: Offset,
    b: Offset,
    routes: List<List<Offset>>,
    minDistanceFromEnds: Float,
): List<EdgeBridge> {
    val horizontal = a.y == b.y && a.x != b.x
    val vertical = a.x == b.x && a.y != b.y
    if (!horizontal && !vertical) return emptyList()
    return routes
        .flatMap { it.zipWithNext() }
        .mapNotNull { (c, d) ->
            val otherHorizontal = c.y == d.y && c.x != d.x
            val otherVertical = c.x == d.x && c.y != d.y
            when {
                horizontal && otherVertical -> crossing(a, b, c, d)?.let { EdgeBridge(it, 0f) }
                vertical && otherHorizontal -> crossing(c, d, a, b)?.let { EdgeBridge(it, 90f) }
                else -> null
            }
        }
        .filter { bridge ->
            distanceAlongSegment(a, b, bridge.center) >= minDistanceFromEnds &&
                distanceAlongSegment(a, b, bridge.center) <= segmentLength(a, b) - minDistanceFromEnds
        }
}

private fun crossing(horizontalStart: Offset, horizontalEnd: Offset, verticalStart: Offset, verticalEnd: Offset): Offset? {
    val minX = minOf(horizontalStart.x, horizontalEnd.x)
    val maxX = maxOf(horizontalStart.x, horizontalEnd.x)
    val minY = minOf(verticalStart.y, verticalEnd.y)
    val maxY = maxOf(verticalStart.y, verticalEnd.y)
    val x = verticalStart.x
    val y = horizontalStart.y
    return if (x in minX..maxX && y in minY..maxY) Offset(x, y) else null
}

private fun segmentLength(a: Offset, b: Offset): Float =
    kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)

private fun distanceAlongSegment(start: Offset, end: Offset, point: Offset): Float =
    if (start.x == end.x) kotlin.math.abs(point.y - start.y) else kotlin.math.abs(point.x - start.x)

private fun DrawScope.drawFacetHandle(
    region: FlowFacetRegion,
    color: Color,
    locked: Boolean,
    collapsed: Boolean,
) {
    locked
    val bounds = region.handleBounds
    val icon = 5.dp.toPx()
    val handleHeight = bounds.height
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.78f),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(8f, 8f),
    )
    drawRoundRect(
        color = color.copy(alpha = 0.82f),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(1.2f),
    )
    repeat(3) { index ->
        val y = region.gripBounds.center.y - 7.dp.toPx() + index * 7.dp.toPx()
        drawLine(
            color = Color.White.copy(alpha = 0.82f),
            start = Offset(region.gripBounds.left + 8.dp.toPx(), y),
            end = Offset(region.gripBounds.right - 8.dp.toPx(), y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.White.copy(alpha = 0.92f).toArgb()
        textSize = handleHeight * 0.38f
    }
    val label = ellipsizeFacetLabel(facetDisplayLabel(region.facet), labelPaint, region.labelBounds.width - 10f)
    drawContext.canvas.nativeCanvas.drawText(
        label,
        region.labelBounds.left + 5f,
        region.labelBounds.center.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
        labelPaint,
    )
    val chevronX = region.collapseBounds.center.x
    val chevronY = bounds.top + handleHeight / 2f
    val chevronDirection = if (collapsed) -1f else 1f
    drawLine(
        color = Color.White.copy(alpha = 0.86f),
        start = Offset(chevronX - icon, chevronY - icon * chevronDirection),
        end = Offset(chevronX, chevronY + icon * chevronDirection),
        strokeWidth = 1.7.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.86f),
        start = Offset(chevronX + icon, chevronY - icon * chevronDirection),
        end = Offset(chevronX, chevronY + icon * chevronDirection),
        strokeWidth = 1.7.dp.toPx(),
        cap = StrokeCap.Round,
    )
    repeat(3) { index ->
        drawCircle(
            color = Color.White.copy(alpha = 0.86f),
            radius = 1.5.dp.toPx(),
            center = Offset(region.menuBounds.center.x, region.menuBounds.center.y - 6.dp.toPx() + index * 6.dp.toPx()),
        )
    }
}

internal fun ellipsizeFacetLabel(label: String, paint: Paint, maxWidth: Float): String {
    if (maxWidth <= 0f) return ""
    if (paint.measureText(label) <= maxWidth) return label
    val suffix = "…"
    var end = label.length
    while (end > 0 && paint.measureText(label.substring(0, end) + suffix) > maxWidth) end--
    return if (end == 0) suffix else label.substring(0, end).trimEnd() + suffix
}

private fun FlowGraphNode.facetContentNodeIds(): Set<FlowNodeId> =
    (properties["nodeIds"] as? FlowSemanticValue.ListValue)
        ?.values
        .orEmpty()
        .mapNotNull { (it as? FlowSemanticValue.StringValue)?.value }
        .map(::FlowNodeId)
        .toSet()

internal data class CollapsedFacetVisibility(
    val hiddenNodeIds: Set<FlowNodeId>,
    val hiddenFacetIds: Set<FlowNodeId>,
)

internal fun collapsedFacetVisibility(
    graph: FlowGraphDocument,
    collapsedFacetNodeIds: Set<FlowNodeId>,
): CollapsedFacetVisibility {
    val facets = graph.nodes.filter { it.isBackgroundFacetNode() }
    val facetsById = facets.associateBy { it.id }
    val hiddenNodes = linkedSetOf<FlowNodeId>()
    val queue = ArrayDeque(collapsedFacetNodeIds.filter { it in facetsById.keys })
    while (queue.isNotEmpty()) {
        val facetId = queue.removeFirst()
        facetsById[facetId]?.facetContentNodeIds().orEmpty().forEach { memberId ->
            if (memberId in facetsById) queue.addLast(memberId) else hiddenNodes += memberId
        }
    }
    val hiddenFacets = facets
        .filter { facet ->
            facet.id !in collapsedFacetNodeIds &&
                facet.facetContentNodeIds().isNotEmpty() &&
                facet.facetContentNodeIds().all { it in hiddenNodes }
        }
        .mapTo(linkedSetOf()) { it.id }
    return CollapsedFacetVisibility(hiddenNodes, hiddenFacets)
}

private fun collapsedFacetContentNodeIds(
    graph: FlowGraphDocument,
    collapsedFacetNodeIds: Set<FlowNodeId>,
): Set<FlowNodeId> = collapsedFacetVisibility(graph, collapsedFacetNodeIds).hiddenNodeIds

internal fun flowchartVisibleEdges(
    edges: List<FlowGraphEdge>,
    config: FlowchartUiConfig,
): List<FlowGraphEdge> =
    edges.filter { edge ->
        config.dataFlowEdgesEnabled || flowEdgeVisualCategory(edge.kind) != FlowchartEdgeVisualCategory.DATA
    }

internal data class FlowchartNodePort(
    val name: String,
    val label: String,
    val kind: FlowEdgeKind,
)

internal data class FlowchartNodePortHit(
    val ref: FlowchartNodePortRef,
    val bounds: Rect,
)

private enum class FlowchartPortSide {
    Top,
    Bottom,
    Left,
    Right,
}

internal fun flowNodePorts(
    node: FlowGraphNode,
    key: String,
): List<FlowchartNodePort> {
    val list = node.properties[key] as? FlowSemanticValue.ListValue ?: return emptyList()
    return list.values.mapNotNull { value ->
        val fields = (value as? FlowSemanticValue.ObjectValue)?.values ?: return@mapNotNull null
        val name = (fields["name"] as? FlowSemanticValue.StringValue)?.value ?: return@mapNotNull null
        val kindName = (fields["kind"] as? FlowSemanticValue.StringValue)?.value ?: return@mapNotNull null
        val kind = FlowEdgeKind.entries.firstOrNull { it.name == kindName } ?: return@mapNotNull null
        val label = (fields["label"] as? FlowSemanticValue.StringValue)?.value ?: name
        FlowchartNodePort(name = name, label = label, kind = kind)
    }
}

private fun FlowGraphNode.isBackgroundFacetNode(): Boolean =
    properties["visualFacet"] == FlowSemanticValue.BooleanValue(true) &&
        properties["syntheticJoin"] != FlowSemanticValue.BooleanValue(true)

private fun DrawScope.drawNodePorts(
    node: FlowGraphNode,
    origin: Offset,
    size: Size,
    zoom: Double,
    config: FlowchartUiConfig,
) {
    drawPortStack(
        node = node,
        origin = origin,
        size = size,
        inputSide = true,
        zoom = zoom,
        config = config,
    )
    drawPortStack(
        node = node,
        origin = origin,
        size = size,
        inputSide = false,
        zoom = zoom,
        config = config,
    )
}

internal fun flowNodePortHits(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    portWidthPx: Float,
    portHeightPx: Float,
): List<FlowchartNodePortHit> {
    val viewport = view.viewport
    fun screen(point: FlowPoint) = Offset(
        (point.x * viewport.zoom + viewport.pan.x).toFloat(),
        (point.y * viewport.zoom + viewport.pan.y).toFloat(),
    )
    return buildList {
        graph.nodes.forEach { node ->
            val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowNodeViewDefaults.StandardSize
            val origin = screen(nodeView.position)
            val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            addAll(flowNodePortHitsForSide(node, origin, canvasSize, inputSide = true, portWidthPx, portHeightPx))
            addAll(flowNodePortHitsForSide(node, origin, canvasSize, inputSide = false, portWidthPx, portHeightPx))
        }
    }
}

private fun flowNodePortHitsForSide(
    node: FlowGraphNode,
    origin: Offset,
    size: Size,
    inputSide: Boolean,
    portWidthPx: Float,
    portHeightPx: Float,
): List<FlowchartNodePortHit> {
    return flowNodePortPlacements(node)
        .filter { it.inputSide == inputSide }
        .map { placement ->
            FlowchartNodePortHit(
                ref = FlowchartNodePortRef(
                    nodeId = node.id,
                    portName = placement.port.name,
                    kind = placement.port.kind,
                    inputSide = inputSide,
                ),
                bounds = portBounds(
                    origin = origin,
                    size = size,
                    side = placement.side,
                    sideIndex = placement.sideIndex,
                    sideCount = placement.sideCount,
                    portWidthPx = portWidthPx,
                    portHeightPx = portHeightPx,
                ),
            )
        }
}

private fun portBounds(
    origin: Offset,
    size: Size,
    side: FlowchartPortSide,
    sideIndex: Int,
    sideCount: Int,
    portWidthPx: Float,
    portHeightPx: Float,
): Rect {
    val verticalWidth = portWidthPx.coerceAtMost(size.width * 0.34f).coerceAtLeast(1f)
    val verticalHeight = portHeightPx.coerceAtLeast(1f)
    val horizontalWidth = portWidthPx.coerceAtMost(size.width * 0.46f).coerceAtLeast(1f)
    val horizontalHeight = portHeightPx.coerceAtLeast(1f)
    return when (side) {
        FlowchartPortSide.Left,
        FlowchartPortSide.Right,
        -> {
            val gap = size.height / (sideCount + 1)
            val x = if (side == FlowchartPortSide.Left) {
                origin.x - verticalWidth * 0.86f
            } else {
                origin.x + size.width - verticalWidth * 0.14f
            }
            val y = origin.y + gap * (sideIndex + 1) - verticalHeight / 2f
            Rect(
                left = x,
                top = y,
                right = x + verticalWidth,
                bottom = y + verticalHeight,
            )
        }
        FlowchartPortSide.Top,
        FlowchartPortSide.Bottom,
        -> {
            val gap = size.width / (sideCount + 1)
            val x = origin.x + gap * (sideIndex + 1) - horizontalWidth / 2f
            val y = if (side == FlowchartPortSide.Top) {
                origin.y - horizontalHeight * 0.86f
            } else {
                origin.y + size.height - horizontalHeight * 0.14f
            }
            Rect(
                left = x,
                top = y,
                right = x + horizontalWidth,
                bottom = y + horizontalHeight,
            )
        }
    }
}

private fun hitNodePort(
    offset: Offset,
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    portWidthPx: Float,
    portHeightPx: Float,
    magnetRadiusPx: Float,
): FlowchartNodePortHit? =
    flowNodePortHits(graph, view, portWidthPx, portHeightPx)
        .filter { hit -> hit.bounds.inflate(magnetRadiusPx).contains(offset) }
        .minByOrNull { hit -> (hit.bounds.center - offset).getDistance() }

private fun DrawScope.drawPortStack(
    node: FlowGraphNode,
    origin: Offset,
    size: Size,
    inputSide: Boolean,
    zoom: Double,
    config: FlowchartUiConfig,
) {
    val visualScale = flowPortVisualScale(zoom)
    val portWidth = 56.dp.toPx() * visualScale
    val portHeight = 22.dp.toPx() * visualScale
    flowNodePortPlacements(node)
        .filter { it.inputSide == inputSide }
        .forEach { placement ->
            val bounds = portBounds(
                origin = origin,
                size = size,
                side = placement.side,
                sideIndex = placement.sideIndex,
                sideCount = placement.sideCount,
                portWidthPx = portWidth,
                portHeightPx = portHeight,
            )
            val color = portColor(placement.port.kind, config.colorTokens)
            drawRoundRect(
                color = color.copy(alpha = 0.92f),
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(bounds.height / 2f, bounds.height / 2f),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.72f),
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = CornerRadius(bounds.height / 2f, bounds.height / 2f),
                style = Stroke(1.25.dp.toPx()),
            )
        }
}

private data class FlowchartPortPlacement(
    val port: FlowchartNodePort,
    val inputSide: Boolean,
    val side: FlowchartPortSide,
    val sideIndex: Int,
    val sideCount: Int,
)

private fun flowNodePortPlacements(node: FlowGraphNode): List<FlowchartPortPlacement> {
    val expanded = buildList {
        listOf(true, false).forEach { inputSide ->
            val key = if (inputSide) "inputPorts" else "outputPorts"
            flowNodePorts(node, key).forEach { port ->
                portSides(node, port, inputSide).forEach { side ->
                    add(Triple(port, inputSide, side))
                }
            }
        }
    }
    return expanded
        .groupBy { it.third }
        .flatMap { (side, sidePorts) ->
            val orderedPorts = if (side == FlowchartPortSide.Bottom) {
                sidePorts.sortedWith(
                    compareBy<Triple<FlowchartNodePort, Boolean, FlowchartPortSide>> {
                        when (it.first.kind) {
                            FlowEdgeKind.FALSE_BRANCH -> 0
                            FlowEdgeKind.SEQUENCE,
                            FlowEdgeKind.LOOP_EXIT -> 1
                            FlowEdgeKind.ELSE_IF_BRANCH -> 2
                            FlowEdgeKind.TRUE_BRANCH -> 3
                            else -> 4
                        }
                    }.thenByDescending { it.first.name },
                )
            } else sidePorts
            orderedPorts.mapIndexed { index, (port, inputSide, _) ->
                FlowchartPortPlacement(port, inputSide, side, index, sidePorts.size)
            }
        }
}

private val bidirectionalDataPortKinds: Set<FlowEdgeKind> = setOf(
    FlowEdgeKind.DATA_FLOW,
    FlowEdgeKind.CONDITION,
)

internal fun FlowGraphNode.usesBidirectionalDataPorts(): Boolean {
    val ports = flowNodePorts(this, "inputPorts") + flowNodePorts(this, "outputPorts")
    return ports.any { it.kind in bidirectionalDataPortKinds } &&
        ports.none { it.kind !in bidirectionalDataPortKinds }
}

private fun portSides(
    node: FlowGraphNode,
    port: FlowchartNodePort,
    inputSide: Boolean,
): List<FlowchartPortSide> =
    if (node.usesBidirectionalDataPorts() && port.kind in bidirectionalDataPortKinds) {
        listOf(FlowchartPortSide.Left, FlowchartPortSide.Right)
    } else {
        listOf(portSide(port, inputSide))
    }

private fun portSide(port: FlowchartNodePort, inputSide: Boolean): FlowchartPortSide =
    if (inputSide) {
        when {
            port.name.equals("previous", ignoreCase = true) -> FlowchartPortSide.Top
            port.kind == FlowEdgeKind.CONDITION || port.kind == FlowEdgeKind.DATA_FLOW -> FlowchartPortSide.Left
            else -> FlowchartPortSide.Top
        }
        } else {
            when {
                port.name.equals("next", ignoreCase = true) -> FlowchartPortSide.Bottom
                port.kind == FlowEdgeKind.TRUE_BRANCH ||
                    port.kind == FlowEdgeKind.ELSE_IF_BRANCH ||
                    port.kind == FlowEdgeKind.FALSE_BRANCH -> FlowchartPortSide.Bottom
                port.kind == FlowEdgeKind.SEQUENCE || port.kind == FlowEdgeKind.LOOP_EXIT -> FlowchartPortSide.Bottom
            port.kind == FlowEdgeKind.LOOP_BODY || port.kind == FlowEdgeKind.LOOP_BACK -> FlowchartPortSide.Left
            else -> FlowchartPortSide.Right
        }
    }

private fun portColor(kind: FlowEdgeKind, tokens: FlowchartColorTokens): Color =
    when (flowEdgeVisualCategory(kind)) {
        FlowchartEdgeVisualCategory.DEFAULT -> tokens.edge
        FlowchartEdgeVisualCategory.BRANCH -> tokens.branchEdge
        FlowchartEdgeVisualCategory.DATA -> tokens.dataEdge
        FlowchartEdgeVisualCategory.LOOP -> tokens.loopEdge
        FlowchartEdgeVisualCategory.ERROR -> tokens.errorEdge
    }

internal fun flowPortVisualScale(zoom: Double): Float =
    zoom.toFloat()
        .coerceIn(0.42f, 1.0f)

private fun flowchartAutoPanDelta(
    point: Offset,
    canvasSize: androidx.compose.ui.unit.IntSize,
    edgePx: Float = 72f,
    stepPx: Float = 24f,
): Offset {
    if (canvasSize.width <= edgePx * 2f || canvasSize.height <= edgePx * 2f) return Offset.Zero
    val panX = when {
        point.x < edgePx -> stepPx
        point.x > canvasSize.width - edgePx -> -stepPx
        else -> 0f
    }
    val panY = when {
        point.y < edgePx -> stepPx
        point.y > canvasSize.height - edgePx -> -stepPx
        else -> 0f
    }
    return Offset(panX, panY)
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
    DATA,
    LOOP,
    ERROR,
}

internal fun flowEdgeVisualCategory(kind: FlowEdgeKind): FlowchartEdgeVisualCategory = when (kind) {
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.FALSE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH,
    FlowEdgeKind.CONDITION -> FlowchartEdgeVisualCategory.BRANCH

    FlowEdgeKind.DATA_FLOW -> FlowchartEdgeVisualCategory.DATA

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

internal fun flowNodeFillColor(
    node: FlowGraphNode,
    tokens: FlowchartColorTokens,
    executionKind: FlowExecutionKind = FlowExecutionKind.WORKFLOW,
): androidx.compose.ui.graphics.Color {
    val blockType = (node.properties["blockType"] as? FlowSemanticValue.StringValue)?.value
    return when {
        node.effectiveTerminatorRole() != null && executionKind == FlowExecutionKind.RECORDING -> tokens.feedbackNodeFill
        node.effectiveTerminatorRole() != null && executionKind == FlowExecutionKind.DRY_RUN -> tokens.debugNodeFill
        blockType == null -> when (node.kind.standard) {
            FlowNodeKind.ENTRY,
            FlowNodeKind.EXIT -> tokens.eventNodeFill
            FlowNodeKind.ACTION -> tokens.actionNodeFill
            FlowNodeKind.DECISION -> tokens.logicNodeFill
            FlowNodeKind.LOOP_START,
            FlowNodeKind.LOOP_END -> tokens.controlNodeFill
            FlowNodeKind.ASSIGNMENT,
            FlowNodeKind.PROPERTY_ACCESS -> tokens.variableNodeFill
            FlowNodeKind.INPUT,
            FlowNodeKind.OUTPUT -> tokens.inputNodeFill
            else -> tokens.nodeFill
        }
        blockType.startsWith("event.") -> tokens.eventNodeFill
        blockType.startsWith("action.") -> tokens.actionNodeFill
        blockType.startsWith("control.") -> tokens.controlNodeFill
        blockType.startsWith("logic.") || blockType.startsWith("literal.") -> tokens.logicNodeFill
        blockType.startsWith("variable.") || blockType.startsWith("variables.") -> tokens.variableNodeFill
        blockType.startsWith("feedback.") -> tokens.feedbackNodeFill
        blockType.startsWith("input.") -> tokens.inputNodeFill
        blockType.startsWith("perception.") || blockType.startsWith("vision.") -> tokens.perceptionNodeFill
        blockType.startsWith("text.") -> tokens.textNodeFill
        blockType.startsWith("file.") -> tokens.fileNodeFill
        blockType.startsWith("system.") -> tokens.systemNodeFill
        blockType.startsWith("chromeTab.") -> tokens.chromeTabNodeFill
        blockType.startsWith("tasker.") -> tokens.taskerNodeFill
        blockType.startsWith("termux.") || blockType.startsWith("shizuku.") || blockType.startsWith("scrcpy.") -> tokens.shellNodeFill
        blockType.startsWith("charts.") -> tokens.chartNodeFill
        blockType.startsWith("runtime.") -> tokens.runtimeNodeFill
        blockType.startsWith("debug.") -> tokens.debugNodeFill
        blockType.startsWith("emscript.command.") -> tokens.actionNodeFill
        else -> tokens.nodeFill
    }
}

internal fun flowNodeDisplayLabel(
    node: FlowGraphNode,
    executionKind: FlowExecutionKind,
): String = when (node.effectiveTerminatorRole()) {
    FlowTerminatorRole.START -> when (executionKind) {
        FlowExecutionKind.WORKFLOW -> "Workflow Start"
        FlowExecutionKind.RECORDING -> "Recording Start"
        FlowExecutionKind.DRY_RUN -> "DryRun Start"
    }
    FlowTerminatorRole.END -> when (executionKind) {
        FlowExecutionKind.WORKFLOW -> "Workflow End"
        FlowExecutionKind.RECORDING -> "Recording End"
        FlowExecutionKind.DRY_RUN -> "DryRun End"
    }
    null -> node.label
}

private fun FlowGraphNode.effectiveTerminatorRole(): FlowTerminatorRole? =
    terminatorRole() ?: when (kind.standard) {
        FlowNodeKind.ENTRY -> FlowTerminatorRole.START
        FlowNodeKind.EXIT -> FlowTerminatorRole.END
        else -> null
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

private fun FlowViewDocument.focusedGraphPoint(
    graph: FlowGraphDocument,
    interaction: FlowInteractionState,
): FlowPoint? =
    interaction.selectedNodeIds.firstOrNull()?.let { nodeId ->
        nodeViews.firstOrNull { it.nodeId == nodeId }?.centerPoint()
    } ?: interaction.selectedEdgeIds.firstOrNull()?.let { edgeId ->
        graph.edges.firstOrNull { it.id == edgeId }?.let { edge ->
            edgeCenter(edge.sourceNodeId, edge.targetNodeId)
        }
    }

private fun FlowNodeView.centerPoint(): FlowPoint {
    val effectiveSize = size ?: FlowSize(128.0, 56.0)
    return FlowPoint(
        x = position.x + effectiveSize.width / 2.0,
        y = position.y + effectiveSize.height / 2.0,
    )
}

private fun FlowViewDocument.edgeCenter(sourceNodeId: FlowNodeId, targetNodeId: FlowNodeId): FlowPoint? {
    val source = nodeViews.firstOrNull { it.nodeId == sourceNodeId }?.centerPoint() ?: return null
    val target = nodeViews.firstOrNull { it.nodeId == targetNodeId }?.centerPoint() ?: return null
    return FlowPoint((source.x + target.x) / 2.0, (source.y + target.y) / 2.0)
}

@Composable
private fun FlowGestureLayer(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    controller: FlowchartController,
    config: FlowchartUiConfig,
    callbacks: FlowchartHostCallbacks,
    collapsedFacetNodeIds: Set<FlowNodeId>,
    lockedFacetNodeIds: Set<FlowNodeId>,
    onToggleFacetCollapsed: (FlowNodeId) -> Unit,
    onToggleFacetLocked: (FlowNodeId) -> Unit,
    refresh: () -> Unit,
) {
    var dragNode by remember { mutableStateOf<FlowNodeId?>(null) }
    var dragNodeGroup by remember { mutableStateOf<Set<FlowNodeId>>(emptySet()) }
    var dragFacet by remember { mutableStateOf<FlowNodeId?>(null) }
    var dragPort by remember { mutableStateOf<FlowchartNodePortRef?>(null) }
    var dragPortAnchor by remember { mutableStateOf<Offset?>(null) }
    var dragPortPointer by remember { mutableStateOf<Offset?>(null) }
    var dragPortTarget by remember { mutableStateOf<FlowchartNodePortHit?>(null) }
    var panning by remember { mutableStateOf(false) }
    var gestureLayerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var latestNodeDragPosition by remember { mutableStateOf<Offset?>(null) }
    val currentView by rememberUpdatedState(view)
    val density = LocalDensity.current
    val portWidthPx = with(density) { 108.dp.toPx() }
    val portHeightPx = with(density) { 68.dp.toPx() }
    val portMagnetRadiusPx = with(density) { 104.dp.toPx() }
    val portDragStartRadiusPx = with(density) { 36.dp.toPx() }
    val platformView = LocalView.current
    val hapticFeedback = LocalHapticFeedback.current
    var previousTapAt by remember { mutableLongStateOf(0L) }
    var previousTapPosition by remember { mutableStateOf<Offset?>(null) }
    var facetMenuRegion by remember(graph.documentRevision) { mutableStateOf<FlowFacetRegion?>(null) }
    LaunchedEffect(dragNode, dragFacet) {
        while (dragNode != null || dragFacet != null) {
            val pointOnScreen = latestNodeDragPosition
            val autoPan = if (pointOnScreen != null) {
                flowchartAutoPanDelta(pointOnScreen, gestureLayerSize)
            } else {
                Offset.Zero
            }
            if (autoPan != Offset.Zero) {
                val current = controller.snapshot().view ?: currentView
                controller.replaceViewport(
                    current.viewport.copy(
                        pan = FlowPoint(
                            x = current.viewport.pan.x + autoPan.x.toDouble(),
                            y = current.viewport.pan.y + autoPan.y.toDouble(),
                        ),
                    ),
                )
                controller.dispatch(
                    FlowInteractionAction.UpdateNodeDrag(
                        FlowPoint(pointOnScreen!!.x.toDouble(), pointOnScreen.y.toDouble()),
                    ),
                )
                callbacks.onNodeDragChanged(
                    dragNode,
                    FlowPoint(pointOnScreen.x.toDouble(), pointOnScreen.y.toDouble()),
                )
                refresh()
            }
            delay(16L)
        }
    }
    val modifier = Modifier.fillMaxSize().testTag("flowchart-gestures")
        .onSizeChanged { gestureLayerSize = it }
        .pointerInput(graph, config.panEnabled, config.zoomEnabled) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                if (!config.panEnabled && !config.zoomEnabled) return@detectTransformGestures
                if (dragPort != null || dragNode != null || dragFacet != null) return@detectTransformGestures
                val current = controller.snapshot().view ?: return@detectTransformGestures
                val old = current.viewport
                val nextZoom = if (config.zoomEnabled) {
                    (old.zoom * zoom.toDouble()).coerceIn(0.1, 8.0)
                } else {
                    old.zoom
                }
                val screenAnchor = FlowPoint(centroid.x.toDouble(), centroid.y.toDouble())
                val graphAnchor = FlowViewportTransform.screenToGraph(screenAnchor, old)
                val panX = if (config.zoomEnabled) screenAnchor.x - graphAnchor.x * nextZoom else old.pan.x
                val panY = if (config.zoomEnabled) screenAnchor.y - graphAnchor.y * nextZoom else old.pan.y
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
                val hiddenNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
                val facetHit = if (config.facetHandlesVisible && controller.snapshot().interaction.facetHandlesVisible) hitFlowFacetHandle(
                    offset = down.position,
                    graph = graph,
                    view = currentView,
                    viewportSize = gestureLayerSize.takeIf { it.width > 0 && it.height > 0 }
                        ?.let { Size(it.width.toFloat(), it.height.toFloat()) },
                    densityScale = density.density,
                    collapsedFacetNodeIds = collapsedFacetNodeIds,
                ) else null
                if (facetHit != null && facetHit.action != FlowFacetHandleAction.Drag) {
                    when (facetHit.action) {
                        FlowFacetHandleAction.Select -> {
                            controller.dispatch(FlowInteractionAction.SelectNode(facetHit.region.facet.id))
                            callbacks.onNodeSelected(facetHit.region.facet.id)
                            callbacks.onEdgeSelected(null)
                        }
                        FlowFacetHandleAction.ToggleCollapse -> onToggleFacetCollapsed(facetHit.region.facet.id)
                        FlowFacetHandleAction.OpenMenu -> facetMenuRegion = facetHit.region
                        FlowFacetHandleAction.ToggleLock -> onToggleFacetLocked(facetHit.region.facet.id)
                        FlowFacetHandleAction.Drag -> Unit
                    }
                    playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Connected, config)
                    refresh()
                    return@awaitEachGesture
                }
                val startPortHit = hitNodePort(down.position, graph, currentView, portWidthPx, portHeightPx, magnetRadiusPx = portDragStartRadiusPx)
                        ?.takeUnless { it.ref.inputSide }
                        ?.takeUnless { it.ref.nodeId in hiddenNodeIds }
                val nodeAtDown = if (startPortHit == null && config.nodeDraggingEnabled) {
                    hitNode(down.position, currentView, hiddenNodeIds)
                } else {
                    null
                }
                val facetDragHit = facetHit?.takeIf {
                    it.action == FlowFacetHandleAction.Drag && it.region.facet.id !in lockedFacetNodeIds
                }
                val dragStart = if (nodeAtDown != null || facetDragHit != null) {
                    awaitLongPressOrCancellation(down.id)
                } else {
                    awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                }
                if (dragStart != null) {
                    dragPort = startPortHit?.ref
                    dragPortAnchor = startPortHit?.bounds?.center
                    dragPortPointer = startPortHit?.bounds?.center
                    dragNode = nodeAtDown
                    if (dragPort != null) {
                        controller.dispatch(FlowInteractionAction.SelectNode(dragPort!!.nodeId))
                        callbacks.onNodeSelected(dragPort!!.nodeId)
                        callbacks.onEdgeSelected(null)
                    } else if (dragNode != null) {
                        val startPoint = FlowPoint(dragStart.position.x.toDouble(), dragStart.position.y.toDouble())
                        controller.dispatch(
                            FlowInteractionAction.BeginNodeDrag(
                                nodeId = dragNode!!,
                                at = startPoint,
                                movementMode = controller.snapshot().interaction.movementMode,
                            ),
                        )
                        dragNodeGroup = controller.snapshot().interaction.dragState?.nodeIds.orEmpty()
                        playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.DragStarted, config)
                        callbacks.onNodeDragChanged(dragNode, startPoint)
                    } else if (facetDragHit != null) {
                        dragFacet = facetDragHit.region.facet.id
                        dragNodeGroup = facetDragHit.region.nodeIds - hiddenNodeIds
                        val startPoint = FlowPoint(dragStart.position.x.toDouble(), dragStart.position.y.toDouble())
                        controller.dispatch(FlowInteractionAction.BeginNodeGroupDrag(dragNodeGroup, startPoint))
                        playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.DragStarted, config)
                    } else if (config.panEnabled) {
                        panning = true
                        controller.dispatch(FlowInteractionAction.BeginViewportPan(FlowPoint(down.position.x.toDouble(), down.position.y.toDouble())))
                    }
                    var latestDragPosition = dragStart.position
                    latestNodeDragPosition = latestDragPosition
                    val completed = drag(dragStart.id) { change ->
                        latestDragPosition = change.position
                        latestNodeDragPosition = change.position
                        if (change.positionChange() != Offset.Zero) change.consume()
                        val point = FlowPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        if (dragPort != null) {
                            dragPortTarget = hitNodePort(change.position, graph, currentView, portWidthPx, portHeightPx, portMagnetRadiusPx)
                                ?.takeIf { hit -> hit.ref.isCompatibleTargetFor(dragPort!!) }
                            dragPortPointer = dragPortTarget?.bounds?.center ?: change.position
                        } else if (dragNode != null || dragFacet != null) {
                            controller.dispatch(
                                FlowInteractionAction.UpdateNodeDrag(
                                    FlowPoint(change.position.x.toDouble(), change.position.y.toDouble()),
                                ),
                            )
                            callbacks.onNodeDragChanged(dragNode, point)
                        }
                        else if (panning) controller.dispatch(FlowInteractionAction.UpdateViewportPan(point))
                        refresh()
                    }
                    if (completed) {
                        val sourcePort = dragPort
                        if (sourcePort != null) {
                            val targetPort = hitNodePort(latestDragPosition, graph, currentView, portWidthPx, portHeightPx, portMagnetRadiusPx)
                                ?.ref
                                ?.takeIf { it.isCompatibleTargetFor(sourcePort) }
                            if (targetPort != null) {
                                playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Connected, config)
                                callbacks.onPortConnectionRequested(sourcePort, targetPort)
                            }
                        } else if (dragNode != null || dragFacet != null) {
                            val finish = FlowPoint(latestDragPosition.x.toDouble(), latestDragPosition.y.toDouble())
                            controller.dispatch(FlowInteractionAction.CommitNodeDrag)
                            playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Dropped, config)
                            dragNode?.let { nodeId ->
                                callbacks.onNodeDragFinished(nodeId, dragNodeGroup.ifEmpty { setOf(nodeId) }, finish)
                            }
                        }
                        else if (panning) controller.dispatch(FlowInteractionAction.CommitViewportPan)
                    } else if (dragNode != null || dragFacet != null) {
                        controller.dispatch(FlowInteractionAction.CancelNodeDrag)
                    }
                    callbacks.onNodeDragChanged(null, null)
                    latestNodeDragPosition = null
                    dragNode = null
                    dragNodeGroup = emptySet()
                    dragFacet = null
                    dragPort = null
                    dragPortAnchor = null
                    dragPortPointer = null
                    dragPortTarget = null
                    panning = false
                    refresh()
                } else if (config.selectionEnabled) {
                    val offset = down.position
                    val prior = previousTapPosition
                    val isDoubleTap = down.uptimeMillis - previousTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                        prior != null && (offset - prior).getDistance() <= viewConfiguration.touchSlop
                    previousTapAt = down.uptimeMillis
                    previousTapPosition = offset
                    val node = hitNode(offset, currentView, hiddenNodeIds)
                    if (isDoubleTap) {
                        node?.let(callbacks.onNodeInvoked)
                    } else {
                        val edge = if (node == null && config.edgeSelectionEnabled) hitEdge(offset, graph, currentView) else null
                        when {
                            node != null -> { controller.dispatch(FlowInteractionAction.SelectNode(node)); callbacks.onNodeSelected(node); callbacks.onEdgeSelected(null) }
                            edge != null -> { controller.dispatch(FlowInteractionAction.SelectEdge(edge)); callbacks.onNodeSelected(null); callbacks.onEdgeSelected(edge) }
                            else -> Unit
                        }
                    }
                    refresh()
                }
            }
        }
    Box(modifier) {
        val anchor = dragPortAnchor
        val pointer = dragPortPointer
        val sourcePort = dragPort
        if (anchor != null && pointer != null && sourcePort != null) {
            Canvas(Modifier.fillMaxSize()) {
                val color = portColor(sourcePort.kind, config.colorTokens)
                val route = portDragPreviewRoute(anchor, dragPortTarget?.bounds?.center ?: pointer)
                route.zipWithNext().forEach { (from, to) ->
                    drawLine(
                        color = color.copy(alpha = 0.86f),
                        start = from,
                        end = to,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(color.copy(alpha = 0.96f), radius = 4.dp.toPx(), center = anchor)
                dragPortTarget?.bounds?.let { target ->
                    drawRoundRect(
                        color = color.copy(alpha = 0.24f),
                        topLeft = target.topLeft,
                        size = target.size,
                        cornerRadius = CornerRadius(target.height / 2f, target.height / 2f),
                    )
                    drawRoundRect(
                        color = color.copy(alpha = 0.92f),
                        topLeft = target.topLeft,
                        size = target.size,
                        cornerRadius = CornerRadius(target.height / 2f, target.height / 2f),
                        style = Stroke(2.dp.toPx()),
                    )
                } ?: drawCircle(color.copy(alpha = 0.72f), radius = 5.dp.toPx(), center = pointer)
            }
        }
        facetMenuRegion?.let { region ->
            val collapsed = region.facet.id in collapsedFacetNodeIds
            Box(
                Modifier.offset {
                    IntOffset(
                        region.menuBounds.left.roundToInt(),
                        region.menuBounds.bottom.roundToInt(),
                    )
                },
            ) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { facetMenuRegion = null },
                ) {
                    Column(
                        Modifier
                            .widthIn(min = 196.dp, max = 280.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = facetDisplayLabel(region.facet),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${facetKindDisplayLabel(region.facet)} · ${region.nodeIds.size} Elemente",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Facet auswählen") },
                        onClick = {
                            controller.dispatch(FlowInteractionAction.SelectNode(region.facet.id))
                            callbacks.onNodeSelected(region.facet.id)
                            callbacks.onEdgeSelected(null)
                            facetMenuRegion = null
                            refresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (collapsed) "Facet ausklappen" else "Facet einklappen") },
                        onClick = {
                            onToggleFacetCollapsed(region.facet.id)
                            facetMenuRegion = null
                            playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Connected, config)
                            refresh()
                        },
                    )
                }
            }
        }
    }
}

internal fun facetKindDisplayLabel(facet: FlowGraphNode): String =
    when ((facet.properties["facetKind"] as? FlowSemanticValue.StringValue)?.value) {
        "VARIABLE_BULK" -> "Variablenbulk"
        "COLLAPSE_GROUP" -> "Collapse-Gruppe"
        "FUNCTION_REGION" -> "Funktionsbereich"
        "BRANCH_REGION" -> "Branch-Bereich"
        "COMMENT_MARKER" -> "Kommentarbereich"
        else -> "Flow-Facet"
    }

private enum class FlowchartFeedbackEvent {
    DragStarted,
    Dropped,
    Deleted,
    Connected,
}

private fun playFlowchartFeedback(
    platformView: android.view.View,
    haptic: HapticFeedback,
    event: FlowchartFeedbackEvent,
    config: FlowchartUiConfig,
) {
    if (config.hapticFeedbackEnabled) {
        val hapticType = when (event) {
            FlowchartFeedbackEvent.DragStarted -> HapticFeedbackType.TextHandleMove
            FlowchartFeedbackEvent.Dropped,
            FlowchartFeedbackEvent.Connected,
            -> HapticFeedbackType.LongPress
            FlowchartFeedbackEvent.Deleted -> HapticFeedbackType.LongPress
        }
        haptic.performHapticFeedback(hapticType)
        val platformHaptic = when (event) {
            FlowchartFeedbackEvent.DragStarted -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            FlowchartFeedbackEvent.Dropped,
            FlowchartFeedbackEvent.Connected,
            -> HapticFeedbackConstants.VIRTUAL_KEY
            FlowchartFeedbackEvent.Deleted -> HapticFeedbackConstants.LONG_PRESS
        }
        platformView.performHapticFeedback(
            platformHaptic,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }
    if (!config.soundEffectsEnabled) return
    platformView.playSoundEffect(SoundEffectConstants.CLICK)
    val (tone, durationMs, volume) = when (event) {
        FlowchartFeedbackEvent.DragStarted -> Triple(ToneGenerator.TONE_PROP_BEEP, 30, 24)
        FlowchartFeedbackEvent.Dropped -> Triple(ToneGenerator.TONE_PROP_BEEP, 30, 20)
        FlowchartFeedbackEvent.Connected -> Triple(ToneGenerator.TONE_PROP_ACK, 44, 32)
        FlowchartFeedbackEvent.Deleted -> Triple(ToneGenerator.TONE_PROP_NACK, 54, 32)
    }
    runCatching {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        generator.startTone(tone, durationMs)
        platformView.postDelayed({ generator.release() }, (durationMs + 40).toLong())
    }
}

internal fun FlowchartNodePortRef.isCompatibleTargetFor(source: FlowchartNodePortRef): Boolean =
    inputSide &&
        !source.inputSide &&
        nodeId != source.nodeId &&
        (kind == source.kind || (kind == FlowEdgeKind.CONDITION && source.kind == FlowEdgeKind.DATA_FLOW))

internal fun portDragPreviewRoute(
    start: Offset,
    end: Offset,
): List<Offset> {
    if (start == end) return listOf(start)
    val midX = (start.x + end.x) / 2f
    return listOf(
        start,
        Offset(midX, start.y),
        Offset(midX, end.y),
        end,
    ).compactOffsets()
}

private fun List<Offset>.compactOffsets(): List<Offset> =
    fold(emptyList<Offset>()) { acc, point ->
        if (acc.lastOrNull() == point) acc else acc + point
    }.removeCollinearOffsets()

private fun List<Offset>.removeCollinearOffsets(): List<Offset> {
    if (size <= 2) return this
    val result = mutableListOf(first())
    for (index in 1 until lastIndex) {
        val previous = result.last()
        val current = this[index]
        val next = this[index + 1]
        val horizontal = previous.y == current.y && current.y == next.y
        val vertical = previous.x == current.x && current.x == next.x
        if (!horizontal && !vertical) result += current
    }
    result += last()
    return result
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

@Composable private fun FlowLabelsAndSemantics(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    state: FlowchartControllerState,
    config: FlowchartUiConfig,
    callbacks: FlowchartHostCallbacks,
    collapsedFacetNodeIds: Set<FlowNodeId>,
) {
    val density = LocalDensity.current
    val executionKind = state.runtime?.takeIf { config.runtimeOverlayEnabled }?.executionKindOrNull()
        ?: graph.executionKind()
    val hiddenNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
    fun xDp(value: Double) = with(density) { value.toFloat().toDp() }
    Box(Modifier.fillMaxSize()) {
        flowchartVisibleEdges(graph.edges, config).forEach { edge ->
            if (edge.sourceNodeId in hiddenNodeIds || edge.targetNodeId in hiddenNodeIds) return@forEach
            if (!flowEdgeLabelVisible(edge, state.interaction, view.viewport.zoom)) return@forEach
            val label = edge.label ?: when (edge.kind) { FlowEdgeKind.TRUE_BRANCH -> "TRUE"; FlowEdgeKind.FALSE_BRANCH -> "FALSE"; FlowEdgeKind.ELSE_IF_BRANCH -> "ELSE IF"; FlowEdgeKind.LOOP_BACK -> "LOOP"; else -> null } ?: return@forEach
            val points = edgeScreenPoints(edge, graph, view)
            val center = points.getOrNull(points.size / 2) ?: return@forEach
            Text(label, Modifier.offset(xDp(center.x), xDp(center.y)).semantics { contentDescription = "Edge $label"; selected = edge.id in state.interaction.selectedEdgeIds; onClick("Select edge") { callbacks.onEdgeSelected(edge.id); true } }, style = MaterialTheme.typography.labelSmall)
        }
        graph.nodes.forEach { node ->
        if (node.isBackgroundFacetNode()) return@forEach
        if (node.id in hiddenNodeIds) return@forEach
        val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
        val runtime = state.runtime.takeIf { config.runtimeOverlayEnabled }?.nodeStates?.get(node.id)
        val displayLabel = flowNodeDisplayLabel(node, executionKind)
        val screen = FlowViewportTransform.graphToScreen(nodeView.position, view.viewport)
        val width = (nodeView.size ?: FlowNodeViewDefaults.StandardSize).width * view.viewport.zoom
        val height = (nodeView.size ?: FlowNodeViewDefaults.StandardSize).height * view.viewport.zoom
        val detailLevel = flowNodeDetailLevel(node, view.viewport.zoom, width, height)
        val contentPadding = if (width < 112.0 || height < 50.0) 2.dp else 8.dp
        val labelAlpha = if (node.id in state.interaction.selectedNodeIds || runtime in setOf(
                FlowRuntimeNodeState.RUNNING,
                FlowRuntimeNodeState.WAITING,
                FlowRuntimeNodeState.FAILED,
            )
        ) 1f else flowNodeGroupAlpha(node.id, graph)
        Box(Modifier.offset(xDp(screen.x), xDp(screen.y)).size(xDp(width), xDp(height)).alpha(labelAlpha).padding(contentPadding).semantics {
            contentDescription = buildString { append(displayLabel); append(", "); append(node.kind.displayName ?: node.kind.standard?.name ?: "extension node"); if (runtime != null) { append(", "); append(runtime.name) }; if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) append(", has diagnostics") }
            selected = node.id in state.interaction.selectedNodeIds
            onClick("Select node") { callbacks.onNodeSelected(node.id); true }
            onLongClick("Invoke node") { callbacks.onNodeInvoked(node.id); true }
        }) {
            if (detailLevel != FlowchartNodeDetailLevel.Dot) {
                Icon(
                    imageVector = flowNodeIcon(node),
                    contentDescription = null,
                    tint = Color(0xFF17121F),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (detailLevel == FlowchartNodeDetailLevel.Full) 30.dp else 20.dp),
                )
            }
        }
    } }
}

private fun flowNodeIcon(node: FlowGraphNode): ImageVector = when (node.effectiveTerminatorRole()) {
    FlowTerminatorRole.START -> Icons.Default.PlayArrow
    FlowTerminatorRole.END -> Icons.Default.Stop
    null -> when (node.kind.standard) {
        FlowNodeKind.DECISION -> Icons.Default.CallSplit
        FlowNodeKind.LOOP_START,
        FlowNodeKind.LOOP_END -> Icons.Default.Loop
        FlowNodeKind.ASSIGNMENT -> Icons.Default.Edit
        FlowNodeKind.PROPERTY_ACCESS -> Icons.Default.DataObject
        FlowNodeKind.INPUT -> Icons.AutoMirrored.Filled.Input
        FlowNodeKind.OUTPUT -> Icons.Default.Output
        FlowNodeKind.ACTION -> Icons.Default.Functions
        else -> Icons.Default.Circle
    }
}

internal fun flowEdgeLabelVisible(
    edge: FlowGraphEdge,
    interaction: FlowInteractionState,
    zoom: Double,
): Boolean {
    if (edge.id in interaction.selectedEdgeIds || edge.sourceNodeId in interaction.selectedNodeIds) return true
    return when (edge.kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.LOOP_BACK,
        -> zoom >= 0.42
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
        -> zoom >= 0.90
        else -> zoom >= 0.65
    }
}

private fun hitNode(
    offset: Offset,
    view: FlowViewDocument,
    hiddenNodeIds: Set<FlowNodeId> = emptySet(),
): FlowNodeId? {
    val graphPoint = FlowViewportTransform.screenToGraph(FlowPoint(offset.x.toDouble(), offset.y.toDouble()), view.viewport)
    return view.nodeViews.asReversed()
        .firstOrNull {
            it.nodeId !in hiddenNodeIds &&
                FlowRect(it.position, it.size ?: FlowNodeViewDefaults.StandardSize).contains(graphPoint)
        }
        ?.nodeId
}

private fun hitEdge(offset: Offset, graph: FlowGraphDocument, view: FlowViewDocument): FlowEdgeId? {
    val point = FlowPoint(offset.x.toDouble(), offset.y.toDouble())
    val segments = graph.edges.associate { edge -> edge.id to edgeScreenPoints(edge, graph, view).zipWithNext() }
    return FlowHitTesting.hitEdge(point, segments, tolerance = 12.0)
}

private fun edgeScreenPoints(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): List<FlowPoint> {
    return edgeGraphPoints(edge, graph, view)
        .map { FlowViewportTransform.graphToScreen(it, view.viewport) }
}

private fun edgeGraphPoints(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): List<FlowPoint> {
    val sourceNode = graph.nodes.firstOrNull { it.id == edge.sourceNodeId } ?: return emptyList()
    val targetNode = graph.nodes.firstOrNull { it.id == edge.targetNodeId } ?: return emptyList()
    val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return emptyList()
    val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return emptyList()
    val sourceRect = FlowRect(source.position, source.size ?: FlowNodeViewDefaults.StandardSize)
    val targetRect = FlowRect(target.position, target.size ?: FlowNodeViewDefaults.StandardSize)
    val start = edgePortOut(edge, sourceNode, sourceRect, targetRect)
    val end = edgePortIn(edge, targetNode, targetRect, sourceRect)
    val obstacles = view.nodeViews
        .filterNot { it.nodeId == edge.sourceNodeId || it.nodeId == edge.targetNodeId }
        .map { FlowRect(it.position, it.size ?: FlowNodeViewDefaults.StandardSize) }
    val edgeView = view.edgeViews.firstOrNull { it.edgeId == edge.id }
    if (edgeView?.routeLockState == FlowRouteLockState.LOCKED && edgeView.bendPoints.isNotEmpty()) {
        return orthogonalize(listOf(start) + edgeView.bendPoints + end)
    }
    if (edgeIsWrapCable(edge, sourceRect, targetRect)) {
        val lift = maxOf(52.0, minOf(132.0, sourceRect.size.height + 36.0))
        return listOf(
            start,
            FlowPoint(sourceRect.right + 42.0, sourceRect.top - lift),
            FlowPoint(targetRect.left - 42.0, targetRect.top - lift),
            end,
        )
    }
    return automaticOrthogonalRoute(edge, start, end, sourceRect, targetRect, obstacles, laneIndex(edge, graph.edges))
}

private fun edgeIsWrapCable(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): Boolean {
    val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return false
    val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return false
    val sourceRect = FlowRect(source.position, source.size ?: FlowNodeViewDefaults.StandardSize)
    val targetRect = FlowRect(target.position, target.size ?: FlowNodeViewDefaults.StandardSize)
    return edgeIsWrapCable(edge, sourceRect, targetRect)
}

private fun edgeIsWrapCable(edge: FlowGraphEdge, source: FlowRect, target: FlowRect): Boolean =
    edge.kind in primaryFlowKinds &&
        target.left > source.right &&
        target.top + target.size.height < source.top + source.size.height

private fun automaticOrthogonalRoute(
    edge: FlowGraphEdge,
    start: FlowPoint,
    end: FlowPoint,
    source: FlowRect,
    target: FlowRect,
    obstacles: Collection<FlowRect>,
    laneIndex: Int,
): List<FlowPoint> {
    val clearance = 28.0
    val lanePadding = laneIndex * 40.0
    val candidates = when (edge.kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
        -> {
            val nearRight = maxOf(source.right, target.right) + clearance + lanePadding
            val widerRight = nearRight + clearance
            val between = if (target.left > source.right) (source.right + target.left) / 2.0 else nearRight
            listOf(between, nearRight, widerRight).distinct().map { lane ->
                listOf(start, FlowPoint(lane, start.y), FlowPoint(lane, end.y), end)
            }
        }
        FlowEdgeKind.LOOP_BODY,
        FlowEdgeKind.LOOP_BACK,
        -> {
            val nearLeft = minOf(source.left, target.left) - clearance - lanePadding
            val widerLeft = nearLeft - clearance
            listOf(nearLeft, widerLeft).map { lane ->
                listOf(start, FlowPoint(lane, start.y), FlowPoint(lane, end.y), end)
            }
        }
        else -> {
            val midY = if (end.y >= start.y) (start.y + end.y) / 2.0 else source.bottom + clearance
            val belowSource = source.bottom + clearance
            val aboveTarget = target.top - clearance
            val horizontalCandidates = listOf(midY, belowSource, aboveTarget).distinct().map { lane ->
                listOf(start, FlowPoint(start.x, lane), FlowPoint(end.x, lane), end)
            }
            val rightLane = maxOf(source.right, target.right) + clearance
            val leftLane = minOf(source.left, target.left) - clearance
            horizontalCandidates + listOf(
                listOf(start, FlowPoint(rightLane, start.y), FlowPoint(rightLane, end.y), end),
                listOf(start, FlowPoint(leftLane, start.y), FlowPoint(leftLane, end.y), end),
            )
        }
    }.map { it.compactOrthogonalPoints() }
    return candidates
        .filterNot { collides(it, obstacles, clearance) }
        .minWithOrNull(
            compareBy<List<FlowPoint>> { directionPenalty(edge, it) }
                .thenBy(::routeLength)
                .thenBy { bendCount(it) }
        )
        ?: candidates.minWithOrNull(
            compareBy<List<FlowPoint>> { collisionCount(it, obstacles, clearance) }
                .thenBy { directionPenalty(edge, it) }
                .thenBy(::routeLength)
                .thenBy { bendCount(it) }
        )
        ?: listOf(start, end)
}

private fun laneIndex(edge: FlowGraphEdge, edges: List<FlowGraphEdge>): Int {
    if (edge.kind !in routedLaneKinds) return 0
    return edges
        .filter { it.sourceNodeId == edge.sourceNodeId && it.kind == edge.kind }
        .sortedWith(compareBy<FlowGraphEdge> { edgeKindOrder(it.kind) }.thenBy { it.label.orEmpty() }.thenBy { it.id.value })
        .indexOfFirst { it.id == edge.id }
        .coerceAtLeast(0)
}

private fun edgeKindOrder(kind: FlowEdgeKind): Int = when (kind) {
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.LOOP_BODY,
    -> 10
    FlowEdgeKind.ELSE_IF_BRANCH -> 20
    FlowEdgeKind.FALSE_BRANCH,
    FlowEdgeKind.LOOP_EXIT,
    -> 30
    FlowEdgeKind.CONDITION,
    FlowEdgeKind.DATA_FLOW,
    -> 35
    FlowEdgeKind.SEQUENCE -> 40
    else -> 50
}

private fun collides(points: List<FlowPoint>, obstacles: Collection<FlowRect>, clearance: Double): Boolean =
    points.zipWithNext().any { (start, end) ->
        obstacles.any { rect -> segmentIntersects(start, end, rect, clearance) }
    }

private fun rangesOverlap(a: Double, b: Double, low: Double, high: Double): Boolean =
    minOf(a, b) <= high && maxOf(a, b) >= low

private fun routeLength(points: List<FlowPoint>): Double =
    points.zipWithNext().sumOf { (from, to) ->
        kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.y - to.y)
    }

private fun bendCount(points: List<FlowPoint>): Int =
    points.compactOrthogonalPoints().size.coerceAtLeast(2) - 2

private fun collisionCount(points: List<FlowPoint>, obstacles: Collection<FlowRect>, clearance: Double): Int =
    points.zipWithNext().sumOf { (start, end) ->
        obstacles.count { rect -> segmentIntersects(start, end, rect, clearance) }
    }

private fun directionPenalty(edge: FlowGraphEdge, points: List<FlowPoint>): Int =
    if (edge.kind in directionalRightOutputKinds && points.any { it.x < points.first().x }) 1 else 0

private fun segmentIntersects(start: FlowPoint, end: FlowPoint, rect: FlowRect, clearance: Double): Boolean {
    val left = rect.left - clearance
    val right = rect.right + clearance
    val top = rect.top - clearance
    val bottom = rect.bottom + clearance
    return if (start.x == end.x) {
        start.x in left..right && rangesOverlap(start.y, end.y, top, bottom)
    } else if (start.y == end.y) {
        start.y in top..bottom && rangesOverlap(start.x, end.x, left, right)
    } else {
        true
    }
}

private fun orthogonalize(points: List<FlowPoint>): List<FlowPoint> {
    if (points.size < 2) return points
    return buildList {
        add(points.first())
        points.zipWithNext().forEach { (from, to) ->
            if (from.x != to.x && from.y != to.y) add(FlowPoint(to.x, from.y))
            add(to)
        }
    }.compactOrthogonalPoints()
}

private fun List<FlowPoint>.compactOrthogonalPoints(): List<FlowPoint> =
    fold(emptyList<FlowPoint>()) { acc, point ->
        if (acc.lastOrNull() == point) acc else acc + point
    }.removeCollinearPoints()

private fun List<FlowPoint>.removeCollinearPoints(): List<FlowPoint> {
    if (size <= 2) return this
    val result = mutableListOf(first())
    for (i in 1 until lastIndex) {
        val prev = result.last()
        val current = this[i]
        val next = this[i + 1]
        val horizontal = prev.y == current.y && current.y == next.y
        val vertical = prev.x == current.x && current.x == next.x
        if (!horizontal && !vertical) result += current
    }
    result += last()
    return result
}

private fun edgePortOut(
    edge: FlowGraphEdge,
    node: FlowGraphNode,
    rect: FlowRect,
    targetRect: FlowRect,
): FlowPoint {
    val portName = when (edge.kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.LOOP_BODY -> edge.label
        FlowEdgeKind.DATA_FLOW,
        FlowEdgeKind.CONDITION -> "output"
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.SEQUENCE,
        FlowEdgeKind.LOOP_EXIT -> null
        else -> null
    }
    if (edge.kind in setOf(FlowEdgeKind.LOOP_BODY, FlowEdgeKind.LOOP_BACK)) {
        return FlowPoint(rect.left, rect.top + rect.size.height / 2.0)
    }
    return sidePort(node, "outputPorts", portName, rect, inputSide = false, peerRect = targetRect)
        ?: if (edge.kind in sideOutputKinds) {
            FlowPoint(rect.right, rect.top + rect.size.height / 2.0)
        } else {
            FlowPoint(rect.left + rect.size.width / 2.0, rect.bottom)
        }
}

private fun edgePortIn(
    edge: FlowGraphEdge,
    node: FlowGraphNode,
    rect: FlowRect,
    sourceRect: FlowRect,
): FlowPoint {
    val portName = when (edge.kind) {
        FlowEdgeKind.DATA_FLOW,
        FlowEdgeKind.CONDITION -> edge.label
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.LOOP_BODY -> "previous"
        else -> null
    }
    return sidePort(node, "inputPorts", portName, rect, inputSide = true, peerRect = sourceRect)
        ?: if (edge.kind in sideInputKinds) {
            FlowPoint(rect.left, rect.top + rect.size.height / 2.0)
        } else {
            FlowPoint(rect.left + rect.size.width / 2.0, rect.top)
        }
}

private fun sidePort(
    node: FlowGraphNode,
    key: String,
    name: String?,
    rect: FlowRect,
    inputSide: Boolean,
    peerRect: FlowRect,
): FlowPoint? {
    name ?: return null
    val ports = flowNodePorts(node, key)
    val index = ports.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
    val port = ports[index]
    val side = routedPortSide(node, port, inputSide, rect, peerRect)
    val portsOnSide = ports.filter { routedPortSide(node, it, inputSide, rect, peerRect) == side }
    val sideIndex = portsOnSide.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0
    return when (side) {
        FlowchartPortSide.Left -> {
            val gap = rect.size.height / (portsOnSide.size + 1)
            FlowPoint(rect.left, rect.top + gap * (sideIndex + 1))
        }
        FlowchartPortSide.Right -> {
            val gap = rect.size.height / (portsOnSide.size + 1)
            FlowPoint(rect.right, rect.top + gap * (sideIndex + 1))
        }
        FlowchartPortSide.Top -> {
            val gap = rect.size.width / (portsOnSide.size + 1)
            FlowPoint(rect.left + gap * (sideIndex + 1), rect.top)
        }
        FlowchartPortSide.Bottom -> {
            val gap = rect.size.width / (portsOnSide.size + 1)
            FlowPoint(rect.left + gap * (sideIndex + 1), rect.bottom)
        }
    }
}

private fun routedPortSide(
    node: FlowGraphNode,
    port: FlowchartNodePort,
    inputSide: Boolean,
    rect: FlowRect,
    peerRect: FlowRect,
): FlowchartPortSide {
    if (!node.usesBidirectionalDataPorts() || port.kind !in bidirectionalDataPortKinds) {
        return portSide(port, inputSide)
    }
    val ownCenter = rect.left + rect.size.width / 2.0
    val peerCenter = peerRect.left + peerRect.size.width / 2.0
    return if (peerCenter < ownCenter) FlowchartPortSide.Left else FlowchartPortSide.Right
}

private val sideOutputKinds: Set<FlowEdgeKind> = setOf(
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH,
    FlowEdgeKind.CONDITION,
    FlowEdgeKind.DATA_FLOW,
)

private val directionalRightOutputKinds: Set<FlowEdgeKind> = setOf(
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH,
)

private val sideInputKinds: Set<FlowEdgeKind> = setOf(
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH,
    FlowEdgeKind.CONDITION,
    FlowEdgeKind.DATA_FLOW,
    FlowEdgeKind.LOOP_BODY,
)

private val routedLaneKinds: Set<FlowEdgeKind> = sideOutputKinds + setOf(
    FlowEdgeKind.LOOP_BODY,
    FlowEdgeKind.LOOP_BACK,
)

private val primaryFlowKinds: Set<FlowEdgeKind> = setOf(
    FlowEdgeKind.SEQUENCE,
    FlowEdgeKind.LOOP_EXIT,
)
