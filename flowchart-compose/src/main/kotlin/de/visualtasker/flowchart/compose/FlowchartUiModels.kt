/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*
import de.visualtasker.flowchart.layout.FlowLayoutOrientation

public enum class FlowchartInteractionMode { VIEW_ONLY, VIEW_INTERACTION }

@Immutable
public data class FlowchartColorTokens(
    public val background: Color = Color(0xFFF8F9FF),
    public val nodeFill: Color = Color(0xFFFFFFFF),
    public val nodeStroke: Color = Color(0xFF3F4758),
    public val selectedStroke: Color = Color(0xFF0057D9),
    public val runningStroke: Color = Color(0xFF006C45),
    public val failedStroke: Color = Color(0xFFBA1A1A),
    public val edge: Color = Color(0xFF596173),
    public val diagnostic: Color = Color(0xFFBA1A1A),
)

@Immutable public data class FlowchartShapeTokens(public val nodeCornerRadiusDp: Float = 12f, public val nodeStrokeWidthDp: Float = 2f)
@Immutable public data class FlowchartTypographyTokens(public val nodeTextSizeSp: Float = 14f, public val edgeTextSizeSp: Float = 11f)
@Immutable public data class FlowchartAccessibilityLabels(public val zoomIn: String = "Zoom in", public val zoomOut: String = "Zoom out", public val centerView: String = "Center flowchart")
@Immutable public data class FlowchartAnimationPreferences(public val enabled: Boolean = true, public val durationMs: Int = 180)

@Immutable
public data class FlowchartUiConfig(
    public val interactionMode: FlowchartInteractionMode = FlowchartInteractionMode.VIEW_INTERACTION,
    public val semanticEditingEnabled: Boolean = false,
    public val panEnabled: Boolean = true,
    public val zoomEnabled: Boolean = true,
    public val nodeDraggingEnabled: Boolean = true,
    public val selectionEnabled: Boolean = true,
    public val edgeSelectionEnabled: Boolean = true,
    public val runtimeOverlayEnabled: Boolean = true,
    public val diagnosticMarkersEnabled: Boolean = true,
    public val minimapEnabled: Boolean = false,
    public val layoutOrientation: FlowLayoutOrientation = FlowLayoutOrientation.TOP_TO_BOTTOM,
    public val shapeTokens: FlowchartShapeTokens = FlowchartShapeTokens(),
    public val colorTokens: FlowchartColorTokens = FlowchartColorTokens(),
    public val typographyTokens: FlowchartTypographyTokens = FlowchartTypographyTokens(),
    public val accessibilityLabels: FlowchartAccessibilityLabels = FlowchartAccessibilityLabels(),
    public val animationPreferences: FlowchartAnimationPreferences = FlowchartAnimationPreferences(),
) { init { require(!semanticEditingEnabled) { "Semantic editing is not supported by the public host" } } }

public data class FlowchartHostCallbacks(
    public val onViewDocumentChanged: (FlowViewDocument) -> Unit = {},
    public val onNodeSelected: (FlowNodeId?) -> Unit = {},
    public val onEdgeSelected: (FlowEdgeId?) -> Unit = {},
    public val onNodeInvoked: (FlowNodeId) -> Unit = {},
    public val onDiagnosticSelected: (FlowDiagnosticId) -> Unit = {},
    public val onRunRequested: (() -> Unit)? = null,
    public val onStatusMessage: (FlowchartStatus) -> Unit = {},
)
