package mg.abela.axiora.cli

import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.validation.ContentValidator
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

internal fun runCli(
    args: Array<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (args.isEmpty() || args[0] != "validate") return usage(err)
    var format = "text"
    var directory: Path? = null
    var i = 1
    while (i < args.size) {
        when {
            args[i] == "--format" && i + 1 < args.size -> format = args[++i]
            args[i].startsWith("--") -> return usage(err)
            directory == null -> directory = Path.of(args[i])
            else -> return usage(err)
        }
        i++
    }
    if (format !in setOf("text", "json") || directory == null) return usage(err)
    val root = directory!!.toAbsolutePath().normalize()
    if (!Files.exists(root) || !Files.isDirectory(root)) {
        err.println("Input directory does not exist: $root")
        return 2
    }
    val result =
        try {
            ContentValidator().validate(root)
        } catch (e: Exception) {
            err.println("Validation could not read input: ${e.message ?: e.javaClass.simpleName}")
            return 2
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
        out.println(json)
    } else if (result.diagnostics.isEmpty()) {
        out.println("Valid content: $root")
    } else {
        result.diagnostics.forEach { d ->
            out.println(
                "${d.severity.uppercase()} ${d.code} ${d.file}:${d.path}: ${d.message}${d.suggestion?.let { " Suggestion: $it" } ?: ""}",
            )
        }
    }
    return if (result.valid) 0 else 1
}

private fun usage(err: PrintStream): Int {
    err.println("Usage: axiora validate <directory> [--format text|json]")
    return 2
}
