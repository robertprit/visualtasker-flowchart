/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.ui.geometry.Offset
import de.visualtasker.flowchart.domain.FlowEdgeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class FlowchartUiConfigTest {
    @Test public fun `public host is semantically read only`() { assertFalse(FlowchartUiConfig().semanticEditingEnabled) }
    @Test public fun `semantic editing cannot be enabled`() { assertThrows(IllegalArgumentException::class.java) { FlowchartUiConfig(semanticEditingEnabled = true) } }

    @Test
    public fun `arrow head follows final routed segment without changing route`() {
        val route = listOf(Offset(10f, 10f), Offset(40f, 10f), Offset(40f, 50f))

        val arrow = flowArrowHead(route, length = 10.0, width = 8.0)

        assertEquals(3, arrow.size)
        assertEquals(40.0, arrow[0].x, 0.0)
        assertEquals(50.0, arrow[0].y, 0.0)
        assertEquals(36.0, arrow[1].x, 0.0)
        assertEquals(40.0, arrow[1].y, 0.0)
        assertEquals(44.0, arrow[2].x, 0.0)
        assertEquals(40.0, arrow[2].y, 0.0)
        assertEquals(listOf(Offset(10f, 10f), Offset(40f, 10f), Offset(40f, 50f)), route)
    }

    @Test
    public fun `arrow head rejects missing and degenerate routes`() {
        assertTrue(flowArrowHead(emptyList(), 10.0, 8.0).isEmpty())
        assertTrue(flowArrowHead(listOf(Offset.Zero, Offset.Zero), 10.0, 8.0).isEmpty())
        assertTrue(flowArrowHead(listOf(Offset.Zero, Offset(1f, 1f)), Double.NaN, 8.0).isEmpty())
    }
}
