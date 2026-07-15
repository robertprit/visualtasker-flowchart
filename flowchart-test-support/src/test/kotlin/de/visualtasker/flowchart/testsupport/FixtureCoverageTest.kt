/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.testsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class FixtureCoverageTest {
    @Test public fun `all required fixtures layout deterministically`() {
        assertEquals(16, FlowchartFixtures.all.size)
        FlowchartFixtures.all.forEach { (name, factory) ->
            val graph = factory()
            val result = FlowchartAssertions.requireDeterministicLayout(graph)
            FlowchartAssertions.requireOrthogonalRoutes(result)
            assertTrue("$name must preserve canonical graph", FlowchartAssertions.requireCanonicalRoundTrip(graph).isNotBlank())
        }
    }
}
