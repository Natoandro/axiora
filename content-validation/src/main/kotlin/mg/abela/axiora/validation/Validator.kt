package mg.abela.axiora.validation

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mg.abela.axiora.content.Revision
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class Diagnostic(val code: String, val severity: String = "error", val file: String, val path: String, val message: String, val suggestion: String? = null)
data class ValidationResult(val diagnostics: List<Diagnostic>) { val valid get() = diagnostics.none { it.severity == "error" } }

class ContentValidator {
    private val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
    private val idPattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    private val supportedCapabilities = setOf("activity.exposition.v1", "activity.choice.v1", "activity.proof.v1", "activity.reflection.v1", "activity.end.v1", "evaluator.exact-choice.v1", "evaluator.self-review.v1")
    private val diagnostics = mutableListOf<Diagnostic>()

    fun validate(directory: Path): ValidationResult {
        diagnostics.clear()
        val root = directory.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return ValidationResult(listOf(Diagnostic("AXC100", file = "", path = "$", message = "Input is not a directory.")))
        val manifest = read(root, "manifest.json", 262_144) ?: return ValidationResult(diagnostics.toList())
        if (!obj(manifest, "manifest.json", "$", setOf("schema_version", "package_id", "version", "title", "language", "license", "requires_capabilities", "conventions", "concept_files", "units", "entry_units", "review", "files"))) return ValidationResult(diagnostics.toList())
        val schema = revision(manifest["schema_version"], "manifest.json", "$.schema_version")
        if (schema != null && schema != Revision(0, 1, 0)) error("AXC101", "manifest.json", "$.schema_version", "Unsupported schema version '$schema'.", "This validator supports 0.1.0.")
        val packageId = id(manifest["package_id"], "manifest.json", "$.package_id")
        revision(manifest["version"], "manifest.json", "$.version")
        string(manifest["title"], "manifest.json", "$.title", 200)
        string(manifest["language"], "manifest.json", "$.language", 64)
        string(manifest["license"], "manifest.json", "$.license", 200)
        string(manifest["conventions"], "manifest.json", "$.conventions", 100_000)
        val caps = stringArray(manifest["requires_capabilities"], "manifest.json", "$.requires_capabilities", nonempty = false)
        caps.filter { it !in supportedCapabilities }.forEach { error("AXC102", "manifest.json", "$.requires_capabilities", "Unsupported required capability '$it'.") }
        val conceptPaths = stringArray(manifest["concept_files"], "manifest.json", "$.concept_files", true)
        val entryUnits = stringArray(manifest["entry_units"], "manifest.json", "$.entry_units", true).mapNotNull { parseId(it, "manifest.json", "$.entry_units") }.toSet()
        val unitRecords = parseManifestUnits(manifest["units"])
        val files = parseFiles(manifest["files"])
        validateReview(manifest["review"])
        val declared = files.map { it.path }.toSet()
        (conceptPaths + unitRecords.map { it.path }).filter { it !in declared }.forEach { error("AXC203", "manifest.json", "$.files", "Referenced payload '$it' is missing from the file inventory.") }
        val inventory = files.associateBy { it.path }
        val actualJson = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it != root.resolve("manifest.json") && it != root.resolve("README.md") }.map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
        (actualJson - declared).forEach { error("AXC203", it, "$", "File is not declared in manifest inventory.") }
        (declared - actualJson).forEach { error("AXC203", "manifest.json", "$.files", "Declared payload '$it' does not exist.") }

        val concepts = linkedMapOf<String, List<Pair<String, String>>>()
        conceptPaths.forEach { path -> read(root, path, 2_097_152)?.let { concepts.putAll(parseConcepts(it, path)) } }
        validatePrerequisiteGraph(concepts)
        val unitIds = mutableSetOf<String>()
        unitRecords.forEach { rec ->
            val unit = read(root, rec.path, 2_097_152) ?: return@forEach
            parseUnit(unit, rec.path, rec.id.value, rec.version.toString())?.let { parsed ->
                if (!unitIds.add(parsed.first)) error("AXC203", rec.path, "$.id", "Duplicate unit ID '${parsed.first}'.")
                if (parsed.first != rec.id.value || parsed.second != rec.version.toString()) error("AXC203", rec.path, "$.id", "Unit identity does not match its manifest record.")
                validateUnit(parsed.third, rec.path, concepts.keys)
            }
        }
        if (unitIds.isNotEmpty() && unitIds != unitRecords.map { it.id.value }.toSet()) error("AXC203", "manifest.json", "$.units", "Manifest unit records do not match readable unit payloads.")
        entryUnits.filter { it !in unitRecords.map { u -> u.id.value } }.forEach { error("AXC203", "manifest.json", "$.entry_units", "Entry unit '$it' is not declared.") }

        files.forEach { file ->
            safePath(file.path, "manifest.json", "$.files")
            inventory[file.path]?.let { f ->
                val target = root.resolve(f.path).normalize()
                if (!target.startsWith(root) || Files.isSymbolicLink(target) || !Files.isRegularFile(target)) return@forEach
                val bytes = Files.readAllBytes(target)
                if (bytes.size.toLong() != f.bytes) error("AXC103", f.path, "$", "Byte count is ${bytes.size}; manifest declares ${f.bytes}.")
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (hash != f.sha256) error("AXC103", f.path, "$", "SHA-256 does not match the manifest.")
            }
        }
        return ValidationResult(diagnostics.toList())
    }

    private fun read(root: Path, relative: String, limit: Int): JsonNode? {
        val normalized = Path.of(relative)
        if (normalized.isAbsolute || normalized.any { it.toString() == ".." } || relative.contains('\\') || relative.isBlank()) {
            error("AXC103", relative, "$", "Unsafe relative path."); return null
        }
        val path = root.resolve(normalized).normalize()
        if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) { error("AXC103", relative, "$", "File is missing or is not a regular file."); return null }
        val size = Files.size(path)
        if (size > limit) { error("AXC103", relative, "$", "File exceeds the ${limit}-byte limit."); return null }
        return try {
            val bytes = Files.readAllBytes(path)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            mapper.readTree(text)
        } catch (e: Exception) {
            val duplicate = e.message?.contains("Duplicate field") == true
            error(if (duplicate) "AXC104" else "AXC100", relative, "$", if (duplicate) "JSON object contains a duplicate key." else "Invalid UTF-8 JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}.")
            null
        }
    }
    private fun obj(n: JsonNode?, file: String, path: String, keys: Set<String>, optional: Set<String> = emptySet()): Boolean {
        if (n == null || !n.isObject) { error("AXC110", file, path, "Expected an object."); return false }
        val actual = n.fieldNames().asSequence().toSet()
        (keys - actual).forEach { error("AXC110", file, "$path.$it", "Required field '$it' is missing.") }
        (actual - keys - optional).forEach { error("AXC110", file, "$path.$it", "Unknown field '$it'.") }
        return true
    }
    private fun error(code: String, file: String, path: String, message: String, suggestion: String? = null) { diagnostics += Diagnostic(code, file = file, path = path, message = message, suggestion = suggestion) }
    private fun warning(code: String, file: String, path: String, message: String) { diagnostics += Diagnostic(code, severity = "warning", file = file, path = path, message = message) }
    private fun string(n: JsonNode?, file: String, path: String, max: Int = 100_000): String? {
        if (n == null || !n.isTextual || n.textValue().isBlank() || n.textValue().length > max) { error("AXC110", file, path, "Expected a nonempty string of at most $max characters."); return null }
        return n.textValue()
    }
    private fun id(n: JsonNode?, file: String, path: String): String? = string(n, file, path, 120)?.let { parseId(it, file, path) }
    private fun parseId(v: String, file: String, path: String): String? = if (idPattern.matches(v)) v else { error("AXC201", file, path, "Invalid stable ID '$v'."); null }
    private fun revision(n: JsonNode?, file: String, path: String): Revision? = string(n, file, path, 32)?.let { Revision.parse(it) ?: run { error("AXC101", file, path, "Expected a three-part numeric version."); null } }
    private fun stringArray(n: JsonNode?, file: String, path: String, nonempty: Boolean): List<String> {
        if (n == null || !n.isArray || (nonempty && n.isEmpty)) { error("AXC110", file, path, "Expected ${if (nonempty) "a nonempty" else "an"} array of strings."); return emptyList() }
        val values = n.mapIndexedNotNull { i, x -> string(x, file, "$path[$i]", 120) }
        if (values.size != values.toSet().size) error("AXC110", file, path, "Array values must be unique.")
        return values
    }
    private fun safePath(value: String, file: String, path: String) { if (value.startsWith('/') || value.contains('\\') || value.split('/').any { it.isEmpty() || it == "." || it == ".." }) error("AXC103", file, path, "Unsafe package path '$value'.") }

    private fun parseManifestUnits(n: JsonNode?): List<mg.abela.axiora.content.ManifestUnit> {
        val f = "manifest.json"; val p = "$.units"; if (n == null || !n.isArray || n.isEmpty) { error("AXC110", f, p, "Expected a nonempty unit array."); return emptyList() }
        return n.mapIndexedNotNull { i, x ->
            val q = "$p[$i]"; if (!obj(x, f, q, setOf("id", "version", "path"))) return@mapIndexedNotNull null
            val id = id(x["id"], f, "$q.id"); val v = revision(x["version"], f, "$q.version"); val path = string(x["path"], f, "$q.path", 240)
            if (path != null) safePath(path, f, "$q.path")
            if (id != null && v != null && path != null) mg.abela.axiora.content.ManifestUnit(mg.abela.axiora.content.StableId(id), v, path) else null
        }
    }
    private fun parseFiles(n: JsonNode?): List<mg.abela.axiora.content.ManifestFile> {
        val f = "manifest.json"; val p = "$.files"; if (n == null || !n.isArray || n.isEmpty) { error("AXC110", f, p, "Expected a nonempty file array."); return emptyList() }
        return n.mapIndexedNotNull { i, x ->
            val q = "$p[$i]"; if (!obj(x, f, q, setOf("path", "bytes", "sha256"))) return@mapIndexedNotNull null
            val path = string(x["path"], f, "$q.path", 240); val size = x["bytes"]?.takeIf { it.isIntegralNumber && it.longValue() >= 0 }?.longValue()
            if (size == null) error("AXC110", f, "$q.bytes", "Expected a nonnegative integer.")
            val hash = string(x["sha256"], f, "$q.sha256", 64)
            if (hash != null && !Regex("[a-f0-9]{64}").matches(hash)) error("AXC110", f, "$q.sha256", "Expected lowercase SHA-256 hex.")
            if (path != null && size != null && hash != null) mg.abela.axiora.content.ManifestFile(path, size, hash) else null
        }
    }
    private fun validateReview(n: JsonNode?) {
        val f = "manifest.json"; if (!obj(n, f, "$.review", setOf("status", "note"))) return
        val status = string(n?.get("status"), f, "$.review.status", 40)
        if (status !in setOf("draft", "mathematically_reviewed", "pilot_reviewed")) error("AXC110", f, "$.review.status", "Unsupported review status.")
        string(n?.get("note"), f, "$.review.note")
        if (status in setOf("mathematically_reviewed", "pilot_reviewed")) warning("AXC501", f, "$.review", "This author-declared review status is not authenticated by the validator.")
    }
    private fun parseConcepts(n: JsonNode, file: String): Map<String, List<Pair<String, String>>> {
        if (!obj(n, file, "$", setOf("schema_version", "concepts"))) return emptyMap()
        if (revision(n["schema_version"], file, "$.schema_version") != Revision(0, 1, 0)) error("AXC101", file, "$.schema_version", "Unsupported schema version.")
        val out = linkedMapOf<String, List<Pair<String, String>>>()
        val array = n["concepts"]
        if (array == null || !array.isArray || array.isEmpty) { error("AXC110", file, "$.concepts", "Expected a nonempty concept array."); return out }
        array.forEachIndexed { i, c ->
            val p = "$.concepts[$i]"; if (!obj(c, file, p, setOf("id", "title", "definition_md", "assumptions", "relations"))) return@forEachIndexed
            val id = id(c["id"], file, "$p.id") ?: return@forEachIndexed
            string(c["title"], file, "$p.title", 200); string(c["definition_md"], file, "$p.definition_md"); string(c["assumptions"], file, "$p.assumptions")
            val rels = mutableListOf<Pair<String, String>>(); val rs = c["relations"]
            if (rs == null || !rs.isArray) error("AXC110", file, "$p.relations", "Expected an array.") else rs.forEachIndexed { j, r ->
                val rp = "$p.relations[$j]"; if (obj(r, file, rp, setOf("type", "target"))) {
                    val type = string(r["type"], file, "$rp.type", 32); val target = id(r["target"], file, "$rp.target")
                    if (type !in setOf("prerequisite", "implies", "equivalent", "generalizes")) error("AXC110", file, "$rp.type", "Unknown relation type.")
                    if (type != null && target != null) rels += type to target
                }
            }
            if (out.put(id, rels) != null) error("AXC203", file, "$p.id", "Duplicate concept ID '$id'.")
        }
        return out
    }
    private fun validatePrerequisiteGraph(concepts: Map<String, List<Pair<String, String>>>) {
        concepts.forEach { (id, relations) -> relations.forEach { (type, target) -> if (target !in concepts) error("AXC203", "concepts", "$.concepts.$id.relations", "Unknown concept '$target'.") } }
        val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
        fun visit(id: String) { if (id in visiting) { error("AXC203", "concepts", "$.concepts", "Prerequisite relations contain a cycle."); return }; if (!done.add(id)) return; visiting += id; concepts[id].orEmpty().filter { it.first == "prerequisite" }.forEach { if (it.second in concepts) visit(it.second) }; visiting -= id }
        concepts.keys.forEach(::visit)
    }

    private fun parseUnit(n: JsonNode, file: String, expectedId: String, expectedVersion: String): Triple<String, String, UnitData>? {
        if (!obj(n, file, "$", setOf("schema_version", "id", "version", "title", "concept_ids", "requires_concepts", "objectives", "sources", "entry", "activities"))) return null
        if (revision(n["schema_version"], file, "$.schema_version") != Revision(0, 1, 0)) error("AXC101", file, "$.schema_version", "Unsupported schema version.")
        val id = id(n["id"], file, "$.id") ?: expectedId
        val version = revision(n["version"], file, "$.version")?.toString() ?: expectedVersion
        string(n["title"], file, "$.title", 200)
        val conceptIds = stringArray(n["concept_ids"], file, "$.concept_ids", true).mapNotNull { parseId(it, file, "$.concept_ids") }.toSet()
        stringArray(n["requires_concepts"], file, "$.requires_concepts", false).forEach { parseId(it, file, "$.requires_concepts") }
        val objectives = mutableListOf<mg.abela.axiora.content.Objective>()
        val os = n["objectives"]
        if (os == null || !os.isArray || os.isEmpty) error("AXC110", file, "$.objectives", "Expected a nonempty array.") else os.forEachIndexed { i, o ->
            val p = "$.objectives[$i]"; if (obj(o, file, p, setOf("id", "concept_id", "skill", "description"))) {
                val oi = id(o["id"], file, "$p.id"); val ci = id(o["concept_id"], file, "$p.concept_id"); val skill = string(o["skill"], file, "$p.skill", 64)
                string(o["description"], file, "$p.description")
                if (ci != null && ci !in conceptIds) error("AXC203", file, "$p.concept_id", "Objective concept is not listed in concept_ids.")
                if (oi != null && ci != null && skill != null) objectives += mg.abela.axiora.content.Objective(oi, mg.abela.axiora.content.StableId(ci), skill)
            }
        }
        val sources = mutableSetOf<String>(); val ss = n["sources"]
        if (ss == null || !ss.isArray || ss.isEmpty) error("AXC110", file, "$.sources", "Expected a nonempty array.") else ss.forEachIndexed { i, s ->
            val p = "$.sources[$i]"; if (obj(s, file, p, setOf("id", "kind", "citation", "locator", "rights"))) {
                val sid = id(s["id"], file, "$p.id"); val kind = string(s["kind"], file, "$p.kind", 32)
                if (kind !in setOf("original", "reference")) error("AXC110", file, "$p.kind", "Source kind must be original or reference.")
                string(s["citation"], file, "$p.citation"); string(s["locator"], file, "$p.locator"); string(s["rights"], file, "$p.rights")
                if (sid != null && !sources.add(sid)) error("AXC203", file, "$p.id", "Duplicate source ID '$sid'.")
            }
        }
        val entry = id(n["entry"], file, "$.entry") ?: ""
        val activities = linkedMapOf<String, mg.abela.axiora.content.Activity>(); val asNode = n["activities"]
        if (asNode == null || !asNode.isObject || asNode.isEmpty) error("AXC110", file, "$.activities", "Expected a nonempty activity object.") else asNode.fieldNames().asSequence().forEach { key ->
            parseActivity(asNode.get(key), file, "$.activities.$key")?.let { activities[key] = it }
        }
        return Triple(id, version, UnitData(conceptIds, objectives, sources, entry, activities))
    }
    private data class UnitData(val conceptIds: Set<String>, val objectives: List<mg.abela.axiora.content.Objective>, val sources: Set<String>, val entry: String, val activities: Map<String, mg.abela.axiora.content.Activity>)

    private fun parseActivity(n: JsonNode, file: String, path: String): mg.abela.axiora.content.Activity? {
        if (!n.isObject) { error("AXC110", file, path, "Expected activity object."); return null }
        val type = string(n["type"], file, "$path.type", 32) ?: return null
        val sourceIds = stringArray(n["source_ids"], file, "$path.source_ids", true)
        fun check(keys: Set<String>, optional: Set<String> = emptySet()) = obj(n, file, path, keys, optional)
        fun next(key: String): String = id(n[key], file, "$path.$key") ?: ""
        return when (type) {
            "exposition" -> if (check(setOf("type", "source_ids", "content_md", "next"))) { string(n["content_md"], file, "$path.content_md"); mg.abela.axiora.content.Exposition(sourceIds, next("next")) } else null
            "choice" -> if (check(setOf("type", "source_ids", "prompt_md", "options", "evaluator", "feedback", "transitions"), setOf("evidence")).not()) null else {
                string(n["prompt_md"], file, "$path.prompt_md")
                val options = mutableSetOf<String>(); val opts = n["options"]
                if (opts == null || !opts.isArray || opts.size() < 2) error("AXC110", file, "$path.options", "Choice requires at least two options.") else opts.forEachIndexed { i, o ->
                    val p = "$path.options[$i]"; if (obj(o, file, p, setOf("id", "text_md"))) { val oid = id(o["id"], file, "$p.id"); string(o["text_md"], file, "$p.text_md"); if (oid != null && !options.add(oid)) error("AXC203", file, "$p.id", "Duplicate option ID.") }
                }
                val evaluator = n["evaluator"]; var expected = ""
                if (obj(evaluator, file, "$path.evaluator", setOf("type", "expected"))) { if (string(evaluator["type"], file, "$path.evaluator.type", 40) != "exact_choice") error("AXC302", file, "$path.evaluator.type", "Unsupported evaluator."); expected = id(evaluator["expected"], file, "$path.evaluator.expected") ?: "" }
                if (expected !in options) error("AXC302", file, "$path.evaluator.expected", "Expected option does not exist.")
                val fb = n["feedback"]; if (obj(fb, file, "$path.feedback", setOf("correct_md", "incorrect_md"))) { string(fb["correct_md"], file, "$path.feedback.correct_md"); string(fb["incorrect_md"], file, "$path.feedback.incorrect_md") }
                val tr = n["transitions"]; var correct = ""; var incorrect = ""
                if (obj(tr, file, "$path.transitions", setOf("correct", "incorrect"))) { correct = nextNode(tr["correct"], file, "$path.transitions.correct"); incorrect = nextNode(tr["incorrect"], file, "$path.transitions.incorrect") }
                var evidence: Pair<String, String>? = null
                n["evidence"]?.let { e -> if (obj(e, file, "$path.evidence", setOf("objective_id", "assessment_family"))) { val objective = id(e["objective_id"], file, "$path.evidence.objective_id") ?: ""; val family = id(e["assessment_family"], file, "$path.evidence.assessment_family") ?: ""; evidence = objective to family } }
                mg.abela.axiora.content.Choice(sourceIds, options, expected, correct, incorrect, evidence)
            }
            "proof" -> if (check(setOf("type", "source_ids", "prompt_md", "hints", "model_solution_md", "rubric", "evaluator", "objective_id", "next"))) {
                string(n["prompt_md"], file, "$path.prompt_md"); string(n["model_solution_md"], file, "$path.model_solution_md")
                val hints = n["hints"]; if (hints == null || !hints.isArray) error("AXC110", file, "$path.hints", "Expected array.") else hints.forEachIndexed { i, h -> val p = "$path.hints[$i]"; if (obj(h, file, p, setOf("id", "content_md"))) { id(h["id"], file, "$p.id"); string(h["content_md"], file, "$p.content_md") } }
                val rubric = n["rubric"]; if (rubric == null || !rubric.isArray || rubric.isEmpty) error("AXC110", file, "$path.rubric", "Expected nonempty rubric.") else rubric.forEachIndexed { i, r -> val p = "$path.rubric[$i]"; if (obj(r, file, p, setOf("id", "criterion_md"))) { id(r["id"], file, "$p.id"); string(r["criterion_md"], file, "$p.criterion_md") } }
                val ev = n["evaluator"]; if (obj(ev, file, "$path.evaluator", setOf("type")) && string(ev["type"], file, "$path.evaluator.type", 40) != "self_review") error("AXC302", file, "$path.evaluator.type", "Proof evaluator must be self_review.")
                mg.abela.axiora.content.Proof(sourceIds, id(n["objective_id"], file, "$path.objective_id") ?: "", next("next"))
            } else null
            "reflection" -> if (check(setOf("type", "source_ids", "prompt_md", "next"))) { string(n["prompt_md"], file, "$path.prompt_md"); mg.abela.axiora.content.Reflection(sourceIds, next("next")) } else null
            "end" -> if (check(setOf("type", "source_ids", "summary_md"))) { string(n["summary_md"], file, "$path.summary_md"); mg.abela.axiora.content.End(sourceIds) } else null
            else -> { error("AXC102", file, "$path.type", "Unsupported activity type '$type'."); null }
        }
    }
    private fun nextNode(n: JsonNode?, file: String, path: String): String = id(n, file, path) ?: ""
    private fun validateUnit(unit: UnitData, file: String, knownConcepts: Set<String>) {
        unit.conceptIds.filter { it !in knownConcepts }.forEach { error("AXC203", file, "$.concept_ids", "Concept '$it' is not defined in this package.") }
        val objectives = unit.objectives.associateBy { it.id }
        unit.objectives.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { error("AXC203", file, "$.objectives", "Duplicate objective ID '$it'.") }
        unit.activities.forEach { (aid, activity) ->
            if (aid != (parseId(aid, file, "$.activities.$aid") ?: aid)) { /* diagnostic supplied above */ }
            activity.sourceIds.filter { it !in unit.sources }.forEach { error("AXC203", file, "$.activities.$aid.source_ids", "Unknown source '$it'.") }
            when (activity) {
                is mg.abela.axiora.content.Choice -> activity.evidence?.let { (objectiveId, family) ->
                    val objective = objectives[objectiveId]
                    if (objective == null) error("AXC203", file, "$.activities.$aid.evidence.objective_id", "Unknown objective '$objectiveId'.")
                    else if (objective.skill !in setOf("recall", "recognition")) error("AXC302", file, "$.activities.$aid.evidence.objective_id", "Exact-choice evidence supports recall or recognition objectives only.")
                    if (family.isBlank()) error("AXC302", file, "$.activities.$aid.evidence.assessment_family", "Assessment family is required.")
                    if (activity.expected !in activity.options) error("AXC302", file, "$.activities.$aid.evaluator.expected", "Expected answer is not an option.")
                }
                is mg.abela.axiora.content.Proof -> { val o = objectives[activity.objectiveId]; if (o == null) error("AXC203", file, "$.activities.$aid.objective_id", "Unknown objective '${activity.objectiveId}'.") else if (o.skill !in setOf("proof_construction", "proof_comprehension")) error("AXC302", file, "$.activities.$aid.objective_id", "Proof self-review requires a proof objective.") }
                else -> Unit
            }
        }
        graph(unit, file)
    }
    private fun graph(unit: UnitData, file: String) {
        val edges = unit.activities.mapValues { (_, a) -> when (a) { is mg.abela.axiora.content.Exposition -> listOf(a.next); is mg.abela.axiora.content.Choice -> listOf(a.correct, a.incorrect); is mg.abela.axiora.content.Proof -> listOf(a.next); is mg.abela.axiora.content.Reflection -> listOf(a.next); is mg.abela.axiora.content.End -> emptyList() } }
        if (unit.entry !in edges) { error("AXC203", file, "$.entry", "Entry activity does not exist."); return }
        edges.forEach { (a, targets) -> targets.filter { it !in edges }.forEach { error("AXC203", file, "$.activities.$a", "Activity target '$it' does not exist.") } }
        val reachable = mutableSetOf<String>(); fun reach(id: String) { if (!reachable.add(id)) return; edges[id].orEmpty().filter { it in edges }.forEach(::reach) }; reach(unit.entry)
        (edges.keys - reachable).forEach { error("AXC203", file, "$.activities.$it", "Activity is unreachable from entry.") }
        val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
        fun acyclic(id: String) { if (id in visiting) { error("AXC203", file, "$.activities.$id", "Activity graph contains a cycle."); return }; if (!done.add(id)) return; visiting += id; edges[id].orEmpty().filter { it in edges }.forEach(::acyclic); visiting -= id }
        edges.keys.forEach(::acyclic)
        val canEnd = mutableSetOf<String>(); val cannotEnd = mutableSetOf<String>(); val checking = mutableSetOf<String>()
        fun terminal(id: String): Boolean {
            if (id in canEnd) return true
            if (id in cannotEnd || !checking.add(id)) return false
            val targets = edges[id].orEmpty()
            val good = if (unit.activities[id] is mg.abela.axiora.content.End) true else targets.isNotEmpty() && targets.all { it in edges && terminal(it) }
            checking -= id
            if (good) canEnd += id else cannotEnd += id
            return good
        }
        edges.keys.forEach { if (!terminal(it)) error("AXC203", file, "$.activities.$it", "Activity has a path that does not terminate in an end activity.") }
    }
}
