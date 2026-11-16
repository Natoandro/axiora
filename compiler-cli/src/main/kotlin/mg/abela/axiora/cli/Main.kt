package mg.abela.axiora.cli

import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.validation.ContentValidator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "validate") usage()
    var format = "text"
    var directory: Path? = null
    var i = 1
    while (i < args.size) {
        when {
            args[i] == "--format" && i + 1 < args.size -> format = args[++i]
            args[i].startsWith("--") -> usage()
            directory == null -> directory = Path.of(args[i])
            else -> usage()
        }
        i++
    }
    if (format !in setOf("text", "json") || directory == null) usage()
    val root = directory.toAbsolutePath().normalize()
    if (!Files.exists(root) || !Files.isDirectory(root)) {
        System.err.println("Input directory does not exist: $root")
        exitProcess(2)
    }
    val result =
        try {
            ContentValidator().validate(root)
        } catch (e: Exception) {
            System.err.println("Validation could not read input: ${e.message ?: e.javaClass.simpleName}")
            exitProcess(2)
        }
    if (format == "json") {
        val json =
            ObjectMapper().writeValueAsString(
                mapOf(
                    "valid" to result.valid,
                    "diagnostics" to
                        result.diagnostics.map { d ->
                            buildMap<String, String> {
                                put("code", d.code)
                                put("severity", d.severity)
                                put("file", d.file)
                                put("path", d.path)
                                put("message", d.message)
                                d.suggestion?.let { put("suggestion", it) }
                            }
                        },
                ),
            )
        println(json)
    } else if (result.diagnostics.isEmpty()) {
        println("Valid content: $root")
    } else {
        result.diagnostics.forEach { d ->
            println(
                "${d.severity.uppercase()} ${d.code} ${d.file}:${d.path}: ${d.message}${d.suggestion?.let { " Suggestion: $it" } ?: ""}",
            )
        }
    }
    exitProcess(if (result.valid) 0 else 1)
}

private fun usage(): Nothing {
    System.err.println("Usage: axiora validate <directory> [--format text|json]")
    exitProcess(2)
}
