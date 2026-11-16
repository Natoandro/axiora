package mg.abela.axiora.validation

import com.fasterxml.jackson.databind.JsonNode
import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Objective
import mg.abela.axiora.content.Revision
import mg.abela.axiora.content.StableId

internal fun ValidationContext.parseUnit(
    n: JsonNode,
    file: String,
    expectedId: String,
    expectedVersion: String,
): Triple<String, String, UnitData>? {
    if (!obj(
            n,
            file,
            "$",
            setOf(
                "schema_version",
                "id",
                "version",
                "title",
                "concept_ids",
                "requires_concepts",
                "objectives",
                "sources",
                "entry",
                "activities",
            ),
        )
    ) {
        return null
    }
    if (revision(n["schema_version"], file, "$.schema_version") !=
        Revision(0, 1, 0)
    ) {
        error("AXC101", file, "$.schema_version", "Unsupported schema version.")
    }
    val id = id(n["id"], file, "$.id") ?: expectedId
    val version = revision(n["version"], file, "$.version")?.toString() ?: expectedVersion
    string(n["title"], file, "$.title", 200)
    val conceptIds =
        stringArray(
            n["concept_ids"],
            file,
            "$.concept_ids",
            true,
        ).mapNotNull { parseId(it, file, "$.concept_ids") }.toSet()
    stringArray(n["requires_concepts"], file, "$.requires_concepts", false).forEach { parseId(it, file, "$.requires_concepts") }
    val objectives = mutableListOf<Objective>()
    val os = n["objectives"]
    if (os == null || !os.isArray ||
        os.isEmpty
    ) {
        error("AXC110", file, "$.objectives", "Expected a nonempty array.")
    } else {
        os.forEachIndexed { i, o ->
            val p = "$.objectives[$i]"
            if (obj(o, file, p, setOf("id", "concept_id", "skill", "description"))) {
                val oi = id(o["id"], file, "$p.id")
                val ci = id(o["concept_id"], file, "$p.concept_id")
                val skill = string(o["skill"], file, "$p.skill", 64)
                if (skill != null &&
                    skill !in setOf("recall", "recognition", "application", "proof_comprehension", "proof_construction", "transfer")
                ) {
                    error("AXC302", file, "$p.skill", "Unsupported objective skill '$skill'.")
                }
                string(o["description"], file, "$p.description")
                if (ci != null &&
                    ci !in conceptIds
                ) {
                    error("AXC203", file, "$p.concept_id", "Objective concept is not listed in concept_ids.")
                }
                if (oi != null && ci != null &&
                    skill != null
                ) {
                    objectives +=
                        Objective(
                            oi,
                            StableId(ci),
                            skill,
                        )
                }
            }
        }
    }
    val sources = mutableSetOf<String>()
    val ss = n["sources"]
    if (ss == null || !ss.isArray ||
        ss.isEmpty
    ) {
        error("AXC110", file, "$.sources", "Expected a nonempty array.")
    } else {
        ss.forEachIndexed { i, s ->
            val p = "$.sources[$i]"
            if (obj(s, file, p, setOf("id", "kind", "citation", "locator", "rights"))) {
                val sid = id(s["id"], file, "$p.id")
                val kind = string(s["kind"], file, "$p.kind", 32)
                if (kind !in
                    setOf("original", "reference")
                ) {
                    error("AXC110", file, "$p.kind", "Source kind must be original or reference.")
                }
                string(s["citation"], file, "$p.citation")
                string(s["locator"], file, "$p.locator")
                string(s["rights"], file, "$p.rights")
                if (sid != null && !sources.add(sid)) error("AXC203", file, "$p.id", "Duplicate source ID '$sid'.")
            }
        }
    }
    val entry = id(n["entry"], file, "$.entry") ?: ""
    val activities = linkedMapOf<String, Activity>()
    val asNode = n["activities"]
    if (asNode == null || !asNode.isObject ||
        asNode.isEmpty
    ) {
        error("AXC110", file, "$.activities", "Expected a nonempty activity object.")
    } else {
        asNode.fieldNames().asSequence().forEach { key ->
            parseActivity(asNode.get(key), file, "$.activities.$key")?.let { activities[key] = it }
        }
    }
    return Triple(id, version, UnitData(conceptIds, objectives, sources, entry, activities))
}

internal data class UnitData(
    val conceptIds: Set<String>,
    val objectives: List<Objective>,
    val sources: Set<String>,
    val entry: String,
    val activities: Map<String, Activity>,
)
