/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.serialization

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.validation.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

public sealed interface FlowDecodeResult<out T> {
    public data class Success<T>(public val value: T, public val validation: FlowValidationResult) : FlowDecodeResult<T>
    public data class UnsupportedSchema(public val version: String) : FlowDecodeResult<Nothing>
    public data class Malformed(public val message: String) : FlowDecodeResult<Nothing>
}

public fun interface FlowDocumentMigrationHook {
    public fun migrate(source: JsonObject, fromVersion: String, targetVersion: String): JsonObject?
}

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    classDiscriminator = "valueType"
}

private fun JsonElement.canonicalized(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.canonicalized() })
    is JsonArray -> JsonArray(map { it.canonicalized() })
    else -> this
}

private fun schemaVersion(element: JsonObject): String? = element["schemaVersion"]?.jsonPrimitive?.contentOrNull
private fun major(version: String): Int? = version.substringBefore('.').toIntOrNull()

private abstract class CanonicalCodec<T>(
    private val serializer: KSerializer<T>,
    private val currentSchema: String,
    private val migrations: List<FlowDocumentMigrationHook>,
) {
    fun encode(value: T): String = canonicalJson.encodeToString(JsonElement.serializer(), canonicalJson.encodeToJsonElement(serializer, value).canonicalized())

    fun decodeValue(text: String): FlowDecodeResult<T> {
        return try {
            var objectValue = canonicalJson.parseToJsonElement(text).jsonObject
            val version = schemaVersion(objectValue) ?: return FlowDecodeResult.Malformed("Missing schemaVersion")
            if (major(version) != major(currentSchema)) return FlowDecodeResult.UnsupportedSchema(version)
            if (version != currentSchema) {
                migrations.firstNotNullOfOrNull { it.migrate(objectValue, version, currentSchema) }?.let { objectValue = it }
            }
            val value = canonicalJson.decodeFromJsonElement(serializer, objectValue)
            FlowDecodeResult.Success(value, validate(value))
        } catch (error: SerializationException) {
            FlowDecodeResult.Malformed(error.message ?: "Malformed JSON")
        } catch (error: IllegalArgumentException) {
            FlowDecodeResult.Malformed(error.message ?: "Malformed document")
        }
    }

    protected abstract fun validate(value: T): FlowValidationResult
}

public class FlowGraphJsonCodec(
    migrations: List<FlowDocumentMigrationHook> = emptyList(),
) {
    private val delegate: CanonicalCodec<FlowGraphDocument> = object : CanonicalCodec<FlowGraphDocument>(FlowGraphDocument.serializer(), FlowGraphDocument.CURRENT_SCHEMA_VERSION, migrations) {
        override fun validate(value: FlowGraphDocument): FlowValidationResult = FlowGraphValidator.validate(value)
    }
    public fun encodeCanonical(document: FlowGraphDocument): String = delegate.encode(document)
    public fun encodeCanonicalUtf8(document: FlowGraphDocument): ByteArray = encodeCanonical(document).encodeToByteArray()
    public fun decode(json: String): FlowDecodeResult<FlowGraphDocument> = delegate.decodeValue(json)
    public fun validateDecoded(document: FlowGraphDocument): FlowValidationResult = FlowGraphValidator.validate(document)
}

public class FlowViewJsonCodec(
    private val graph: FlowGraphDocument,
    migrations: List<FlowDocumentMigrationHook> = emptyList(),
) {
    private val delegate: CanonicalCodec<FlowViewDocument> = object : CanonicalCodec<FlowViewDocument>(FlowViewDocument.serializer(), FlowViewDocument.CURRENT_SCHEMA_VERSION, migrations) {
        override fun validate(value: FlowViewDocument): FlowValidationResult = FlowViewValidator.validate(graph, value)
    }
    public fun encodeCanonical(document: FlowViewDocument): String = delegate.encode(document)
    public fun decode(json: String): FlowDecodeResult<FlowViewDocument> = delegate.decodeValue(json)
    public fun validateDecoded(document: FlowViewDocument): FlowValidationResult = FlowViewValidator.validate(graph, document)
}

public class FlowRuntimeJsonCodec(
    private val graph: FlowGraphDocument,
    private val context: FlowRuntimeValidationContext = FlowRuntimeValidationContext(),
    migrations: List<FlowDocumentMigrationHook> = emptyList(),
) {
    private val delegate: CanonicalCodec<FlowRuntimeSnapshot> = object : CanonicalCodec<FlowRuntimeSnapshot>(FlowRuntimeSnapshot.serializer(), FlowRuntimeSnapshot.CURRENT_SCHEMA_VERSION, migrations) {
        override fun validate(value: FlowRuntimeSnapshot): FlowValidationResult = FlowRuntimeSnapshotValidator.validate(graph, value, context)
    }
    public fun encodeCanonical(document: FlowRuntimeSnapshot): String = delegate.encode(document)
    public fun decode(json: String): FlowDecodeResult<FlowRuntimeSnapshot> = delegate.decodeValue(json)
    public fun validateDecoded(document: FlowRuntimeSnapshot): FlowValidationResult = FlowRuntimeSnapshotValidator.validate(graph, document, context)
}
