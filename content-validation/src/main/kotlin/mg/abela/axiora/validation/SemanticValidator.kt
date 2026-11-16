package mg.abela.axiora.validation

import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection

internal fun ValidationContext.validateUnit(
    unit: UnitData,
    file: String,
    knownConcepts: Set<String>,
    usedCapabilities: MutableSet<String>,
) {
    unit.conceptIds
        .filter {
            it !in knownConcepts
        }.forEach { error("AXC203", file, "$.concept_ids", "Concept '$it' is not defined in this package.") }
    val objectives = unit.objectives.associateBy { it.id }
    unit.objectives
        .groupBy {
            it.id
        }.filterValues { it.size > 1 }
        .keys
        .forEach { error("AXC203", file, "$.objectives", "Duplicate objective ID '$it'.") }
    unit.activities.forEach { (aid, activity) ->
        parseId(aid, file, "$.activities.$aid")
        activity.sourceIds.filter { it !in unit.sources }.forEach {
            error(
                "AXC203",
                file,
                "$.activities.$aid.source_ids",
                "Unknown source '$it'.",
            )
        }
        when (activity) {
            is Exposition -> {
                usedCapabilities += "activity.exposition.v1"
            }

            is Choice -> {
                usedCapabilities += "activity.choice.v1"
                usedCapabilities += "evaluator.exact-choice.v1"
                activity.evidence?.let { (objectiveId, family) ->
                    val objective = objectives[objectiveId]
                    if (objective ==
                        null
                    ) {
                        error("AXC203", file, "$.activities.$aid.evidence.objective_id", "Unknown objective '$objectiveId'.")
                    } else if (objective.skill !in
                        setOf("recall", "recognition")
                    ) {
                        error(
                            "AXC302",
                            file,
                            "$.activities.$aid.evidence.objective_id",
                            "Exact-choice evidence supports recall or recognition objectives only.",
                        )
                    }
                    if (family.isBlank()) {
                        error(
                            "AXC302",
                            file,
                            "$.activities.$aid.evidence.assessment_family",
                            "Assessment family is required.",
                        )
                    }
                    if (activity.expected !in
                        activity.options
                    ) {
                        error("AXC302", file, "$.activities.$aid.evaluator.expected", "Expected answer is not an option.")
                    }
                }
            }

            is Proof -> {
                usedCapabilities += "activity.proof.v1"
                usedCapabilities += "evaluator.self-review.v1"
                val o = objectives[activity.objectiveId]
                if (o ==
                    null
                ) {
                    error("AXC203", file, "$.activities.$aid.objective_id", "Unknown objective '${activity.objectiveId}'.")
                } else if (o.skill !in
                    setOf("proof_construction", "proof_comprehension")
                ) {
                    error("AXC302", file, "$.activities.$aid.objective_id", "Proof self-review requires a proof objective.")
                }
            }

            is Reflection -> {
                usedCapabilities += "activity.reflection.v1"
            }

            is End -> {
                usedCapabilities += "activity.end.v1"
            }
        }
    }
    graph(unit, file)
}
