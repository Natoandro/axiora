package mg.abela.axiora.validation

import com.fasterxml.jackson.databind.JsonNode
import mg.abela.axiora.content.Revision

internal fun ValidationContext.parseConcepts(
    n: JsonNode,
    file: String,
): Map<String, List<Pair<String, String>>> {
    if (!obj(n, file, "$", setOf("schema_version", "concepts"))) return emptyMap()
    if (revision(n["schema_version"], file, "$.schema_version") !=
        Revision(0, 1, 0)
    ) {
        error("AXC101", file, "$.schema_version", "Unsupported schema version.")
    }
    val out = linkedMapOf<String, List<Pair<String, String>>>()
    val array = n["concepts"]
    if (array == null || !array.isArray ||
        array.isEmpty
    ) {
        error("AXC110", file, "$.concepts", "Expected a nonempty concept array.")
        return out
    }
    array.forEachIndexed { i, c ->
        val p = "$.concepts[$i]"
        if (!obj(c, file, p, setOf("id", "title", "definition_md", "assumptions", "relations"))) return@forEachIndexed
        val id = id(c["id"], file, "$p.id") ?: return@forEachIndexed
        string(c["title"], file, "$p.title", 200)
        string(c["definition_md"], file, "$p.definition_md")
        string(c["assumptions"], file, "$p.assumptions")
        val rels = mutableListOf<Pair<String, String>>()
        val rs = c["relations"]
        if (rs == null || !rs.isArray) {
            error("AXC110", file, "$p.relations", "Expected an array.")
        } else {
            rs.forEachIndexed { j, r ->
                val rp = "$p.relations[$j]"
                if (obj(r, file, rp, setOf("type", "target"))) {
                    val type = string(r["type"], file, "$rp.type", 32)
                    val target = id(r["target"], file, "$rp.target")
                    if (type !in
                        setOf("prerequisite", "implies", "equivalent", "generalizes")
                    ) {
                        error("AXC110", file, "$rp.type", "Unknown relation type.")
                    }
                    if (type != null && target != null) rels += type to target
                }
            }
        }
        if (out.put(id, rels) != null) error("AXC203", file, "$p.id", "Duplicate concept ID '$id'.")
    }
    return out
}

internal fun ValidationContext.validatePrerequisiteGraph(concepts: Map<String, List<Pair<String, String>>>) {
    concepts.forEach { (id, relations) ->
        relations.forEach { (type, target) ->
            if (target !in
                concepts
            ) {
                error("AXC203", "concepts", "$.concepts.$id.relations", "Unknown concept '$target'.")
            }
        }
    }
    val visiting = mutableSetOf<String>()
    val done = mutableSetOf<String>()

    fun visit(id: String) {
        if (id in
            visiting
        ) {
            error("AXC203", "concepts", "$.concepts", "Prerequisite relations contain a cycle.")
            return
        }
        if (!done.add(id)) return
        visiting +=
            id
        concepts[id].orEmpty().filter { it.first == "prerequisite" }.forEach {
            if (it.second in
                concepts
            ) {
                visit(it.second)
            }
        }
        visiting -= id
    }
    concepts.keys.forEach(::visit)
}
