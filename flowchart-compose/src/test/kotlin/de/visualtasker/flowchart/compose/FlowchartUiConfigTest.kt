/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

public class FlowchartUiConfigTest {
    @Test public fun `public host is semantically read only`() { assertFalse(FlowchartUiConfig().semanticEditingEnabled) }
    @Test public fun `semantic editing cannot be enabled`() { assertThrows(IllegalArgumentException::class.java) { FlowchartUiConfig(semanticEditingEnabled = true) } }
}
