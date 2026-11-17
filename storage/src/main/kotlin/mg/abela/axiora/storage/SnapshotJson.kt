package mg.abela.axiora.storage

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64

/** Strict, versioned application backup envelope. BLOBs are explicit base64 strings. */
internal object SnapshotJson {
    private val mapper =
        ObjectMapper(
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build(),
        )
    private const val VERSION = 1

    fun encode(tables: Map<String, List<Map<String, Any?>>>): ByteArray {
        val root = mapper.createObjectNode().put("format", "axiora-learner-backup").put("version", VERSION)
        val tableObject = root.putObject("tables")
        tables.forEach { (table, records) ->
            val array = tableObject.putArray(table)
            records.forEach { record ->
                val item = array.addObject()
                record.forEach { (key, value) ->
                    when (value) {
                        null -> item.putNull(key)
                        is ByteArray -> item.put(key, Base64.getEncoder().encodeToString(value))
                        is Number -> item.put(key, value.toLong())
                        else -> item.put(key, value.toString())
                    }
                }
            }
        }
        return mapper.writeValueAsBytes(root)
    }

    fun decode(bytes: ByteArray): Map<String, List<Map<String, Any?>>> {
        val root = mapper.readTree(bytes)
        require(root is ObjectNode && root.size() == 3) { "Backup envelope is invalid." }
        require(root.path("format").asText() == "axiora-learner-backup" && root.path("version").asInt() == VERSION) {
            "Backup format version is unsupported."
        }
        val tableObject = root.path("tables")
        require(tableObject is ObjectNode) { "Backup table data is invalid." }
        return tableObject.fields().asSequence().associate { (table, node) ->
            require(node.isArray) { "Backup table '$table' is invalid." }
            table to
                node.map { record ->
                    require(record is ObjectNode) { "Backup record in '$table' is invalid." }
                    record.fields().asSequence().associate { (key, value) ->
                        val decoded = value(value)
                        key to
                            if (key in BLOB_COLUMNS) {
                                require(decoded is String) { "Backup binary field is invalid." }
                                try {
                                    Base64.getDecoder().decode(decoded)
                                } catch (_: IllegalArgumentException) {
                                    throw IllegalArgumentException("Backup binary field is invalid.")
                                }
                            } else {
                                decoded
                            }
                    }
                }
        }
    }

    private fun value(node: JsonNode): Any? =
        when {
            node.isNull -> null
            node.isIntegralNumber -> node.longValue()
            node.isTextual -> node.textValue()
            node.isBoolean -> node.booleanValue()
            else -> throw IllegalArgumentException("Backup field type is unsupported.")
        }

    private val BLOB_COLUMNS = setOf("checkpoint", "result_payload", "assistance", "payload", "supporting_attempts")
}
