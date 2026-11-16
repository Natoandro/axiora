package mg.abela.axiora.validation

import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection

internal fun ValidationContext.graph(
    unit: UnitData,
    file: String,
) {
    val edges =
        unit.activities.mapValues { (_, a) ->
            when (a) {
                is Exposition -> listOf(a.next)
                is Choice -> listOf(a.correct, a.incorrect)
                is Proof -> listOf(a.next)
                is Reflection -> listOf(a.next)
                is End -> emptyList()
            }
        }
    if (unit.entry !in edges) {
        error("AXC203", file, "$.entry", "Entry activity does not exist.")
        return
    }
    edges.forEach { (a, targets) ->
        targets.filter { it !in edges }.forEach { error("AXC203", file, "$.activities.$a", "Activity target '$it' does not exist.") }
    }
    val reachable = mutableSetOf<String>()

    fun reach(id: String) {
        if (!reachable.add(id)) return
        edges[id]
            .orEmpty()
            .filter {
                it in
                    edges
            }.forEach(::reach)
    }
    reach(unit.entry)
    (edges.keys - reachable).forEach { error("AXC203", file, "$.activities.$it", "Activity is unreachable from entry.") }
    val visiting = mutableSetOf<String>()
    val done = mutableSetOf<String>()

    fun acyclic(id: String) {
        if (id in
            visiting
        ) {
            error("AXC203", file, "$.activities.$id", "Activity graph contains a cycle.")
            return
        }
        if (!done.add(id)) return
        visiting +=
            id
        edges[id].orEmpty().filter { it in edges }.forEach(::acyclic)
        visiting -= id
    }
    edges.keys.forEach(::acyclic)
    val canEnd = mutableSetOf<String>()
    val cannotEnd = mutableSetOf<String>()
    val checking = mutableSetOf<String>()

    fun terminal(id: String): Boolean {
        if (id in canEnd) return true
        if (id in cannotEnd || !checking.add(id)) return false
        val targets = edges[id].orEmpty()
        val good =
            if (unit.activities[id] is End) {
                true
            } else {
                targets.isNotEmpty() &&
                    targets.all { it in edges && terminal(it) }
            }
        checking -= id
        if (good) canEnd += id else cannotEnd += id
        return good
    }
    edges.keys.forEach {
        if (!terminal(it)) error("AXC203", file, "$.activities.$it", "Activity has a path that does not terminate in an end activity.")
    }
}
