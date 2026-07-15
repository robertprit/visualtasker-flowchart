/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
public class DemoDeviceSmokeTest {
    @get:Rule public val compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule()

    @Test public fun fixturesAndViewRuntimeControlsRemainInteractive() {
        compose.onNodeWithText("Fixture: Linear").assertIsDisplayed()
        compose.onNodeWithContentDescription("Entry, ENTRY").assertExists()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Center flowchart").performClick()

        selectFixture("Branch")
        compose.onNodeWithContentDescription("Decision, DECISION").assertExists()
        compose.onNodeWithText("TRUE").assertExists()
        compose.onNodeWithText("FALSE").assertExists()

        selectFixture("Simple loop")
        compose.onNodeWithText("LOOP").assertExists()
        compose.onNodeWithContentDescription("Body, ACTION").performTouchInput { swipeRight() }

        revealToolbarEnd()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Redo").performClick()
        compose.onNodeWithText("Runtime").performClick()
        compose.onNodeWithText("Serialize").performClick()
        compose.onNodeWithText("Reload view").performClick()
        compose.onNodeWithText("Replace revision").performClick()
        compose.waitUntilAtLeastOneExists(hasText("RUNTIME_REJECTED", substring = true), 5_000)

        selectFixture("Unknown extension")
        compose.onNodeWithContentDescription("Vendor, capability").assertExists()
    }

    private fun selectFixture(name: String) {
        revealToolbarStart()
        compose.onNode(hasText("Fixture:", substring = true)).performClick()
        compose.onNodeWithText(name, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun revealToolbarEnd() {
        compose.onNode(hasScrollAction()).performTouchInput { swipeLeft(startX = right - 20f, endX = left + 20f) }
        compose.waitForIdle()
    }

    private fun revealToolbarStart() {
        compose.onNode(hasScrollAction()).performTouchInput { swipeRight(startX = left + 20f, endX = right - 20f) }
        compose.waitForIdle()
    }
}
