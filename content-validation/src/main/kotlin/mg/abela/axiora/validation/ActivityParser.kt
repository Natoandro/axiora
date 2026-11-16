package mg.abela.axiora.validation

import com.fasterxml.jackson.databind.JsonNode
import mg.abela.axiora.content.Activity
import mg.abela.axiora.content.Choice
import mg.abela.axiora.content.End
import mg.abela.axiora.content.Exposition
import mg.abela.axiora.content.Proof
import mg.abela.axiora.content.Reflection

internal fun ValidationContext.parseActivity(
    n: JsonNode,
    file: String,
    path: String,
): Activity? {
    if (!n.isObject) {
        error("AXC110", file, path, "Expected activity object.")
        return null
    }
    val type = string(n["type"], file, "$path.type", 32) ?: return null
    val sourceIds = stringArray(n["source_ids"], file, "$path.source_ids", true)

    fun check(
        keys: Set<String>,
        optional: Set<String> = emptySet(),
    ) = obj(n, file, path, keys, optional)

    fun next(key: String): String = id(n[key], file, "$path.$key") ?: ""
    return when (type) {
        "exposition" -> {
            if (check(setOf("type", "source_ids", "content_md", "next"))) {
                string(n["content_md"], file, "$path.content_md")
                Exposition(sourceIds, next("next"))
            } else {
                null
            }
        }

        "choice" -> {
            if (check(
                    setOf("type", "source_ids", "prompt_md", "options", "evaluator", "feedback", "transitions"),
                    setOf("evidence"),
                ).not()
            ) {
                null
            } else {
                string(n["prompt_md"], file, "$path.prompt_md")
                val options = mutableSetOf<String>()
                val opts = n["options"]
                if (opts == null || !opts.isArray ||
                    opts.size() < 2
                ) {
                    error("AXC110", file, "$path.options", "Choice requires at least two options.")
                } else {
                    opts.forEachIndexed { i, o ->
                        val p = "$path.options[$i]"
                        if (obj(o, file, p, setOf("id", "text_md"))) {
                            val oid = id(o["id"], file, "$p.id")
                            string(o["text_md"], file, "$p.text_md")
                            if (oid !=
                                null &&
                                !options.add(oid)
                            ) {
                                error("AXC203", file, "$p.id", "Duplicate option ID.")
                            }
                        }
                    }
                }
                val evaluator = n["evaluator"]
                var expected = ""
                if (obj(evaluator, file, "$path.evaluator", setOf("type", "expected"))) {
                    if (string(evaluator["type"], file, "$path.evaluator.type", 40) !=
                        "exact_choice"
                    ) {
                        error("AXC302", file, "$path.evaluator.type", "Unsupported evaluator.")
                    }
                    expected =
                        id(evaluator["expected"], file, "$path.evaluator.expected") ?: ""
                }
                if (expected !in options) error("AXC302", file, "$path.evaluator.expected", "Expected option does not exist.")
                val fb = n["feedback"]
                if (obj(fb, file, "$path.feedback", setOf("correct_md", "incorrect_md"))) {
                    string(fb["correct_md"], file, "$path.feedback.correct_md")
                    string(fb["incorrect_md"], file, "$path.feedback.incorrect_md")
                }
                val tr = n["transitions"]
                var correct = ""
                var incorrect = ""
                if (obj(tr, file, "$path.transitions", setOf("correct", "incorrect"))) {
                    correct =
                        nextNode(tr["correct"], file, "$path.transitions.correct")
                    incorrect =
                        nextNode(tr["incorrect"], file, "$path.transitions.incorrect")
                }
                var evidence: Pair<String, String>? = null
                n["evidence"]?.let { e ->
                    if (obj(e, file, "$path.evidence", setOf("objective_id", "assessment_family"))) {
                        val objective =
                            id(e["objective_id"], file, "$path.evidence.objective_id") ?: ""
                        val family =
                            id(e["assessment_family"], file, "$path.evidence.assessment_family") ?: ""
                        evidence = objective to family
                    }
                }
                Choice(sourceIds, options, expected, correct, incorrect, evidence)
            }
        }

        "proof" -> {
            if (check(
                    setOf(
                        "type",
                        "source_ids",
                        "prompt_md",
                        "hints",
                        "model_solution_md",
                        "rubric",
                        "evaluator",
                        "objective_id",
                        "next",
                    ),
                )
            ) {
                string(n["prompt_md"], file, "$path.prompt_md")
                string(n["model_solution_md"], file, "$path.model_solution_md")
                val hints = n["hints"]
                val hintIds = mutableSetOf<String>()
                if (hints == null ||
                    !hints.isArray
                ) {
                    error("AXC110", file, "$path.hints", "Expected array.")
                } else {
                    hints.forEachIndexed {
                        i,
                        h,
                        ->
                        val p = "$path.hints[$i]"
                        if (obj(h, file, p, setOf("id", "content_md"))) {
                            id(h["id"], file, "$p.id")?.let(hintIds::add)
                            string(h["content_md"], file, "$p.content_md")
                        }
                    }
                }
                val rubric = n["rubric"]
                val rubricIds = mutableSetOf<String>()
                if (rubric == null || !rubric.isArray ||
                    rubric.isEmpty
                ) {
                    error("AXC110", file, "$path.rubric", "Expected nonempty rubric.")
                } else {
                    rubric.forEachIndexed {
                        i,
                        r,
                        ->
                        val p = "$path.rubric[$i]"
                        if (obj(r, file, p, setOf("id", "criterion_md"))) {
                            id(r["id"], file, "$p.id")?.let(rubricIds::add)
                            string(r["criterion_md"], file, "$p.criterion_md")
                        }
                    }
                }
                val ev = n["evaluator"]
                if (obj(ev, file, "$path.evaluator", setOf("type")) &&
                    string(ev["type"], file, "$path.evaluator.type", 40) != "self_review"
                ) {
                    error("AXC302", file, "$path.evaluator.type", "Proof evaluator must be self_review.")
                }
                Proof(sourceIds, id(n["objective_id"], file, "$path.objective_id") ?: "", next("next"), hintIds, rubricIds)
            } else {
                null
            }
        }

        "reflection" -> {
            if (check(setOf("type", "source_ids", "prompt_md", "next"))) {
                string(n["prompt_md"], file, "$path.prompt_md")
                Reflection(sourceIds, next("next"))
            } else {
                null
            }
        }

        "end" -> {
            if (check(setOf("type", "source_ids", "summary_md"))) {
                string(n["summary_md"], file, "$path.summary_md")
                End(sourceIds)
            } else {
                null
            }
        }

        else -> {
            error("AXC102", file, "$path.type", "Unsupported activity type '$type'.")
            null
        }
    }
}

internal fun ValidationContext.nextNode(
    n: JsonNode?,
    file: String,
    path: String,
): String = id(n, file, path) ?: ""
