/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.visualtasker.flowchart.compose.*
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*
import de.visualtasker.flowchart.layout.*
import de.visualtasker.flowchart.serialization.*
import de.visualtasker.flowchart.testsupport.*

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FlowchartDemo() } }
    }
}

@Composable
private fun FlowchartDemo() {
    var fixtureName by remember { mutableStateOf("Linear") }
    var revision by remember { mutableIntStateOf(1) }
    var graph by remember(fixtureName, revision) { mutableStateOf(FlowchartFixtures.all.getValue(fixtureName)().copy(documentRevision = FlowDocumentRevision(revision.toString()))) }
    var orientation by remember { mutableStateOf(FlowLayoutOrientation.TOP_TO_BOTTOM) }
    var movement by remember { mutableStateOf(FlowMovementMode.SINGLE) }
    var runtime by remember { mutableStateOf<FlowRuntimeSnapshot?>(null) }
    var savedViewJson by remember { mutableStateOf<String?>(null) }
    var restoredView by remember { mutableStateOf<FlowViewDocument?>(null) }
    var diagnostics by remember { mutableStateOf("Ready") }
    var fixtureMenu by remember { mutableStateOf(false) }
    val controller = remember(orientation, fixtureName, revision) { FlowchartController(FlowSurfaceId("demo"), layoutConfig = FlowLayoutConfig(orientation = orientation)) }
    DisposableEffect(controller) { onDispose(controller::close) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box { Button({ fixtureMenu = true }) { Text("Fixture: $fixtureName") }; DropdownMenu(fixtureMenu, { fixtureMenu = false }) { FlowchartFixtures.all.keys.forEach { name -> DropdownMenuItem({ Text(name) }, { fixtureName = name; graph = FlowchartFixtures.all.getValue(name)(); revision = 1; runtime = null; restoredView = null; fixtureMenu = false }) } } }
            Button({ orientation = if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) FlowLayoutOrientation.LEFT_TO_RIGHT else FlowLayoutOrientation.TOP_TO_BOTTOM }) { Text("Orientation: ${orientation.name}") }
            Button({ controller.replaceLayout(FlowLayoutConfig(orientation = orientation)) }) { Text("Layout") }
            Button({ movement = if (movement == FlowMovementMode.SINGLE) FlowMovementMode.CONNECTED_BFS else FlowMovementMode.SINGLE; controller.dispatch(FlowInteractionAction.SetMovementMode(movement)) }) { Text("Move: ${movement.name}") }
            Button({ controller.dispatch(FlowInteractionAction.UndoViewChange) }) { Text("Undo") }
            Button({ controller.dispatch(FlowInteractionAction.RedoViewChange) }) { Text("Redo") }
            Button({ runtime = simulateRuntime(graph, runtime) }) { Text("Runtime") }
            Button({ savedViewJson = controller.snapshot().view?.let { FlowViewJsonCodec(graph).encodeCanonical(it) }; diagnostics = "Graph ${FlowGraphJsonCodec().encodeCanonical(graph).length} bytes; view saved" }) { Text("Serialize") }
            Button({ restoredView = savedViewJson?.let { (FlowViewJsonCodec(graph).decode(it) as? FlowDecodeResult.Success)?.value }; diagnostics = if (restoredView != null) "View reloaded" else "No compatible saved view" }) { Text("Reload view") }
            Button({ revision++; graph = graph.copy(documentRevision = FlowDocumentRevision(revision.toString())); diagnostics = "Graph revision replaced; stale view/runtime rejected" }) { Text("Replace revision") }
        }
        Text("$diagnostics · semantic editing disabled · unknown extensions render generically", Modifier.padding(horizontal = 12.dp))
        FlowchartHost(
            graphDocument = graph,
            viewDocument = restoredView,
            runtimeSnapshot = runtime,
            controller = controller,
            uiConfig = FlowchartUiConfig(layoutOrientation = orientation),
            callbacks = FlowchartHostCallbacks(onStatusMessage = { diagnostics = "${it.code}: ${it.message}" }),
        )
    }
}

private fun simulateRuntime(graph: FlowGraphDocument, previous: FlowRuntimeSnapshot?): FlowRuntimeSnapshot {
    val sequence = (previous?.sequence ?: 0) + 1
    val activeIndex = (sequence % graph.nodes.size.coerceAtLeast(1)).toInt()
    val states = graph.nodes.mapIndexed { index, node -> node.id to when { index == activeIndex -> FlowRuntimeNodeState.RUNNING; index < activeIndex -> FlowRuntimeNodeState.SUCCEEDED; else -> FlowRuntimeNodeState.NOT_STARTED } }.toMap()
    return FlowRuntimeSnapshotBuilder.forGraph(graph, sequence, states)
}
