package mg.abela.axiora.validation

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ContentValidator {
    fun validate(directory: Path): ValidationResult = ValidationContext().validate(directory)
}

internal fun ValidationContext.validate(directory: Path): ValidationResult {
    diagnostics.clear()
    val root = directory.toAbsolutePath().normalize()
    if (!Files.isDirectory(
            root,
        )
    ) {
        return ValidationResult(listOf(Diagnostic("AXC100", file = "", path = "$", message = "Input is not a directory.")))
    }
    val manifest = read(root, "manifest.json", 262_144) ?: return ValidationResult(diagnostics.toList())
    if (!obj(
            manifest,
            "manifest.json",
            "$",
            setOf(
                "schema_version",
                "package_id",
                "version",
                "title",
                "language",
                "license",
                "requires_capabilities",
                "conventions",
                "concept_files",
                "units",
                "entry_units",
                "review",
                "files",
            ),
        )
    ) {
        return ValidationResult(diagnostics.toList())
    }
    val schema = revision(manifest["schema_version"], "manifest.json", "$.schema_version")
    if (schema != null &&
        schema.toString() != "0.1.0"
    ) {
        error(
            "AXC101",
            "manifest.json",
            "$.schema_version",
            "Unsupported schema version '$schema'.",
            "This validator supports 0.1.0.",
        )
    }
    val packageId = id(manifest["package_id"], "manifest.json", "$.package_id")
    revision(manifest["version"], "manifest.json", "$.version")
    string(manifest["title"], "manifest.json", "$.title", 200)
    string(manifest["language"], "manifest.json", "$.language", 64)
    string(manifest["license"], "manifest.json", "$.license", 200)
    string(manifest["conventions"], "manifest.json", "$.conventions", 100_000)
    val caps = stringArray(manifest["requires_capabilities"], "manifest.json", "$.requires_capabilities", nonempty = false)
    caps
        .filter {
            it !in supportedCapabilities
        }.forEach { error("AXC102", "manifest.json", "$.requires_capabilities", "Unsupported required capability '$it'.") }
    val conceptPaths = stringArray(manifest["concept_files"], "manifest.json", "$.concept_files", true)
    val entryUnits =
        stringArray(manifest["entry_units"], "manifest.json", "$.entry_units", true)
            .mapNotNull {
                parseId(it, "manifest.json", "$.entry_units")
            }.toSet()
    val unitRecords = parseManifestUnits(manifest["units"])
    val files = parseFiles(manifest["files"])
    validateReview(manifest["review"])
    duplicateValues(conceptPaths, "manifest.json", "$.concept_files", "Concept path")
    duplicateValues(unitRecords.map { it.id.value }, "manifest.json", "$.units", "Unit ID")
    duplicateValues(unitRecords.map { it.path }, "manifest.json", "$.units", "Unit path")
    duplicateValues(files.map { it.path }, "manifest.json", "$.files", "File path")
    val foldedPaths = files.map { it.path.lowercase() }
    if (foldedPaths.size != foldedPaths.toSet().size) {
        error("AXC103", "manifest.json", "$.files", "Payload paths must not collide case-insensitively.")
    }
    duplicateValues(conceptPaths + unitRecords.map { it.path }, "manifest.json", "$.files", "Payload ownership")
    val declared = files.map { it.path }.toSet()
    (
        conceptPaths +
            unitRecords.map {
                it.path
            }
    ).filter {
        it !in declared
    }.forEach { error("AXC203", "manifest.json", "$.files", "Referenced payload '$it' is missing from the file inventory.") }
    val inventory = files.associateBy { it.path }
    val actualJson =
        Files.walk(root).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) && it != root.resolve("manifest.json") &&
                        it != root.resolve("README.md")
                }.map { root.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }
    (actualJson - declared).forEach { error("AXC203", it, "$", "File is not declared in manifest inventory.") }
    (declared - actualJson).forEach { error("AXC203", "manifest.json", "$.files", "Declared payload '$it' does not exist.") }

    val concepts = linkedMapOf<String, List<Pair<String, String>>>()
    conceptPaths.forEach { path -> read(root, path, 2_097_152)?.let { concepts.putAll(parseConcepts(it, path)) } }
    validatePrerequisiteGraph(concepts)
    val unitIds = mutableSetOf<String>()
    val usedCapabilities = mutableSetOf<String>()
    unitRecords.forEach { rec ->
        val unit = read(root, rec.path, 2_097_152) ?: return@forEach
        parseUnit(unit, rec.path, rec.id.value, rec.version.toString())?.let { parsed ->
            if (!unitIds.add(parsed.first)) error("AXC203", rec.path, "$.id", "Duplicate unit ID '${parsed.first}'.")
            if (parsed.first != rec.id.value ||
                parsed.second != rec.version.toString()
            ) {
                error("AXC203", rec.path, "$.id", "Unit identity does not match its manifest record.")
            }
            validateUnit(parsed.third, rec.path, concepts.keys, usedCapabilities)
        }
    }
    if (unitIds.isNotEmpty() &&
        unitIds != unitRecords.map { it.id.value }.toSet()
    ) {
        error("AXC203", "manifest.json", "$.units", "Manifest unit records do not match readable unit payloads.")
    }
    entryUnits
        .filter {
            it !in
                unitRecords.map { u ->
                    u.id.value
                }
        }.forEach { error("AXC203", "manifest.json", "$.entry_units", "Entry unit '$it' is not declared.") }
    (usedCapabilities - caps.toSet()).forEach {
        error("AXC102", "manifest.json", "$.requires_capabilities", "Manifest omits required capability '$it'.")
    }

    files.forEach { file ->
        safePath(file.path, "manifest.json", "$.files")
        inventory[file.path]?.let { f ->
            val target = root.resolve(f.path).normalize()
            if (!target.startsWith(root) || Files.isSymbolicLink(target) || !Files.isRegularFile(target)) return@forEach
            val bytes = Files.readAllBytes(target)
            if (bytes.size.toLong() !=
                f.bytes
            ) {
                error("AXC103", f.path, "$", "Byte count is ${bytes.size}; manifest declares ${f.bytes}.")
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (hash != f.sha256) error("AXC103", f.path, "$", "SHA-256 does not match the manifest.")
        }
    }
    return ValidationResult(diagnostics.toList())
}
