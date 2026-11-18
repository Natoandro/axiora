package mg.abela.axiora.validation

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.content.LearningUnit
import mg.abela.axiora.content.Revision
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class Diagnostic(
    val code: String,
    val severity: String = "error",
    val file: String,
    val path: String,
    val message: String,
    val suggestion: String? = null,
)

data class ValidationResult(
    val diagnostics: List<Diagnostic>,
    val units: List<LearningUnit> = emptyList(),
) {
    val valid get() = diagnostics.none { it.severity == "error" }
}

internal class ValidationContext {
    internal val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
    internal val idPattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    internal val supportedCapabilities =
        setOf(
            "activity.exposition.v1",
            "activity.choice.v1",
            "activity.proof.v1",
            "activity.reflection.v1",
            "activity.end.v1",
            "evaluator.exact-choice.v1",
            "evaluator.self-review.v1",
        )
    internal val diagnostics = mutableListOf<Diagnostic>()

    internal fun read(
        root: Path,
        relative: String,
        limit: Int,
    ): JsonNode? {
        val normalized = Paths.get(relative)
        if (normalized.isAbsolute || normalized.any { it.toString() == ".." } || relative.contains('\\') || relative.isBlank()) {
            error("AXC103", relative, "$", "Unsafe relative path.")
            return null
        }
        val path = root.resolve(normalized).normalize()
        if (!path.startsWith(root) || Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path)
        ) {
            error("AXC103", relative, "$", "File is missing or is not a regular file.")
            return null
        }
        val size = Files.size(path)
        if (size > limit) {
            error("AXC103", relative, "$", "File exceeds the $limit-byte limit.")
            return null
        }
        return try {
            val bytes = Files.readAllBytes(path)
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            mapper.readTree(text)
        } catch (e: Exception) {
            val duplicate = e.message?.contains("Duplicate field") == true
            val message =
                if (duplicate) {
                    "JSON object contains a duplicate key."
                } else {
                    "Invalid UTF-8 JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}."
                }
            error(
                if (duplicate) "AXC104" else "AXC100",
                relative,
                "$",
                message,
            )
            null
        }
    }

    internal fun obj(
        n: JsonNode?,
        file: String,
        path: String,
        keys: Set<String>,
        optional: Set<String> = emptySet(),
    ): Boolean {
        if (n == null || !n.isObject) {
            error("AXC110", file, path, "Expected an object.")
            return false
        }
        val actual = n.fieldNames().asSequence().toSet()
        (keys - actual).forEach { error("AXC110", file, "$path.$it", "Required field '$it' is missing.") }
        (actual - keys - optional).forEach { error("AXC110", file, "$path.$it", "Unknown field '$it'.") }
        return true
    }

    internal fun error(
        code: String,
        file: String,
        path: String,
        message: String,
        suggestion: String? = null,
    ) {
        diagnostics +=
            Diagnostic(code, file = file, path = path, message = message, suggestion = suggestion)
    }

    internal fun duplicateValues(
        values: List<String>,
        file: String,
        path: String,
        label: String,
    ) {
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            error("AXC203", file, path, "$label '$it' is declared more than once.")
        }
    }

    internal fun warning(
        code: String,
        file: String,
        path: String,
        message: String,
    ) {
        diagnostics +=
            Diagnostic(code, severity = "warning", file = file, path = path, message = message)
    }

    internal fun string(
        n: JsonNode?,
        file: String,
        path: String,
        max: Int = 100_000,
    ): String? {
        if (n == null || !n.isTextual || n.textValue().isBlank() ||
            n.textValue().length > max
        ) {
            error("AXC110", file, path, "Expected a nonempty string of at most $max characters.")
            return null
        }
        return n.textValue()
    }

    internal fun id(
        n: JsonNode?,
        file: String,
        path: String,
    ): String? = string(n, file, path, 120)?.let { parseId(it, file, path) }

    internal fun parseId(
        v: String,
        file: String,
        path: String,
    ): String? =
        if (idPattern.matches(v)) {
            v
        } else {
            error("AXC201", file, path, "Invalid stable ID '$v'.")
            null
        }

    internal fun revision(
        n: JsonNode?,
        file: String,
        path: String,
    ): Revision? =
        string(n, file, path, 32)?.let {
            Revision.parse(it)
                ?: run {
                    error("AXC101", file, path, "Expected a three-part numeric version.")
                    null
                }
        }

    internal fun stringArray(
        n: JsonNode?,
        file: String,
        path: String,
        nonempty: Boolean,
    ): List<String> {
        if (n == null || !n.isArray ||
            (nonempty && n.isEmpty)
        ) {
            error("AXC110", file, path, "Expected ${if (nonempty) "a nonempty" else "an"} array of strings.")
            return emptyList()
        }
        val values = n.mapIndexedNotNull { i, x -> string(x, file, "$path[$i]", 120) }
        if (values.size != values.toSet().size) error("AXC110", file, path, "Array values must be unique.")
        return values
    }

    internal fun safePath(
        value: String,
        file: String,
        path: String,
    ) {
        if (value.startsWith('/') || value.contains('\\') ||
            value.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            error("AXC103", file, path, "Unsafe package path '$value'.")
        }
    }
}
