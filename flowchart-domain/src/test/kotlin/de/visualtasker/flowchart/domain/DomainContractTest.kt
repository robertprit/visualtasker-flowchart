/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class DomainContractTest {
    @Test public fun `blank IDs are rejected and compare by value`() {
        assertThrows(IllegalArgumentException::class.java) { FlowNodeId(" ") }
        assertEquals(FlowNodeId("n1"), FlowNodeId("n1"))
    }

    @Test public fun `source span rejects reversed ranges`() {
        assertThrows(IllegalArgumentException::class.java) { FlowSourceSpan(2, 1, 1, 1, 1, 1) }
    }

    @Test public fun `extension kind stays producer defined`() {
        val kind = FlowSemanticKind(extensionId = "vendor.capability", displayName = "Capability")
        assertEquals("vendor.capability", kind.extensionId)
        assertEquals(null, kind.standard)
    }
}
