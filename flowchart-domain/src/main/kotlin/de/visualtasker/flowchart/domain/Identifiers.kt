/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import kotlinx.serialization.Serializable

private fun requireIdentifier(value: String): String = value.also { require(it.isNotBlank()) { "Identifier must not be blank" } }

@JvmInline @Serializable public value class FlowDocumentId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowDocumentRevision(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowNodeId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowEdgeId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowDiagnosticId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowSurfaceId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowRunId(public val value: String) { init { requireIdentifier(value) } }
@JvmInline @Serializable public value class FlowSourceSessionId(public val value: String) { init { requireIdentifier(value) } }
