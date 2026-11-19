use super::*;

pub(super) fn parse_choice(
    validator: &mut Validator,
    item: &Value,
    file: &str,
    id: &str,
    objectives: &BTreeMap<String, Objective>,
) {
    let path = format!("$.activities.{id}");
    validator.object_optional(
        Some(item),
        file,
        &path,
        &[
            "type",
            "source_ids",
            "prompt_md",
            "options",
            "evaluator",
            "feedback",
            "transitions",
        ],
        &["evidence"],
    );
    nonempty(
        item.get("prompt_md"),
        validator,
        file,
        &format!("{path}.prompt_md"),
    );
    let Some(Value::Array(options)) = item.get("options") else {
        validator.error(
            "AXC110",
            file,
            &format!("{path}.options"),
            "Expected at least two options.",
        );
        return;
    };
    if options.len() < 2 {
        validator.error(
            "AXC110",
            file,
            &format!("{path}.options"),
            "Expected at least two options.",
        );
    }
    let mut option_ids = HashSet::new();
    for (i, option) in options.iter().enumerate() {
        let p = format!("{path}.options[{i}]");
        if !validator.object(Some(option), file, &p, &["id", "text_md"]) {
            continue;
        }
        if let Some(option_id) = validator.id(option.get("id"), file, &format!("{p}.id")) {
            if !option_ids.insert(option_id.clone()) {
                validator.error(
                    "AXC203",
                    file,
                    &format!("{p}.id"),
                    format!("Duplicate option ID '{option_id}'."),
                );
            }
        }
        nonempty(
            option.get("text_md"),
            validator,
            file,
            &format!("{p}.text_md"),
        );
    }
    let evaluator = item.get("evaluator");
    if validator.object(
        evaluator,
        file,
        &format!("{path}.evaluator"),
        &["type", "expected"],
    ) {
        if evaluator
            .and_then(|v| v.get("type"))
            .and_then(Value::as_str)
            != Some("exact_choice")
        {
            validator.error(
                "AXC302",
                file,
                &format!("{path}.evaluator.type"),
                "Only exact_choice is supported.",
            );
        }
        let expected = evaluator
            .and_then(|v| v.get("expected"))
            .and_then(Value::as_str);
        if let Some(expected) = expected {
            if !option_ids.contains(expected) {
                validator.error(
                    "AXC302",
                    file,
                    &format!("{path}.evaluator.expected"),
                    "Expected answer is not an option.",
                );
            }
        }
    }
    if validator.object(
        item.get("feedback"),
        file,
        &format!("{path}.feedback"),
        &["correct_md", "incorrect_md"],
    ) {
        nonempty(
            item.get("feedback").and_then(|v| v.get("correct_md")),
            validator,
            file,
            &format!("{path}.feedback.correct_md"),
        );
        nonempty(
            item.get("feedback").and_then(|v| v.get("incorrect_md")),
            validator,
            file,
            &format!("{path}.feedback.incorrect_md"),
        );
    }
    if let Some(evidence) = item.get("evidence") {
        if validator.object(
            Some(evidence),
            file,
            &format!("{path}.evidence"),
            &["objective_id", "assessment_family"],
        ) {
            let objective = validator.id(
                evidence.get("objective_id"),
                file,
                &format!("{path}.evidence.objective_id"),
            );
            let family = validator.string(
                evidence.get("assessment_family"),
                file,
                &format!("{path}.evidence.assessment_family"),
                120,
            );
            if let Some(objective) = objective {
                match objectives.get(&objective) {
                    Some(o) if !matches!(o.skill.as_str(), "recall" | "recognition") => validator
                        .error(
                            "AXC302",
                            file,
                            &format!("{path}.evidence.objective_id"),
                            "Exact-choice evidence supports recall or recognition objectives only.",
                        ),
                    None => validator.error(
                        "AXC203",
                        file,
                        &format!("{path}.evidence.objective_id"),
                        format!("Unknown objective '{objective}'."),
                    ),
                    _ => (),
                }
            }
            if family.as_deref().is_some_and(str::is_empty) {
                validator.error(
                    "AXC302",
                    file,
                    &format!("{path}.evidence.assessment_family"),
                    "Assessment family is required.",
                );
            }
        }
    }
}

pub(super) fn parse_proof(
    validator: &mut Validator,
    item: &Value,
    file: &str,
    id: &str,
    objectives: &BTreeMap<String, Objective>,
) {
    let path = format!("$.activities.{id}");
    validator.object(
        Some(item),
        file,
        &path,
        &[
            "type",
            "source_ids",
            "prompt_md",
            "hints",
            "model_solution_md",
            "rubric",
            "evaluator",
            "objective_id",
            "next",
        ],
    );
    nonempty(
        item.get("prompt_md"),
        validator,
        file,
        &format!("{path}.prompt_md"),
    );
    nonempty(
        item.get("model_solution_md"),
        validator,
        file,
        &format!("{path}.model_solution_md"),
    );
    for (field, item_fields, empty_allowed) in [
        ("hints", ["id", "content_md"].as_slice(), true),
        ("rubric", ["id", "criterion_md"].as_slice(), false),
    ] {
        match item.get(field) {
            Some(Value::Array(entries)) if empty_allowed || !entries.is_empty() => {
                for (index, entry) in entries.iter().enumerate() {
                    let entry_path = format!("{path}.{field}[{index}]");
                    if validator.object(Some(entry), file, &entry_path, item_fields) {
                        validator.id(entry.get("id"), file, &format!("{entry_path}.id"));
                        let text_key = if field == "hints" {
                            "content_md"
                        } else {
                            "criterion_md"
                        };
                        nonempty(
                            entry.get(text_key),
                            validator,
                            file,
                            &format!("{entry_path}.{text_key}"),
                        );
                    }
                }
            }
            Some(Value::Array(_)) => validator.error(
                "AXC110",
                file,
                &format!("{path}.{field}"),
                "Expected nonempty rubric.",
            ),
            _ => validator.error(
                "AXC110",
                file,
                &format!("{path}.{field}"),
                "Expected array.",
            ),
        }
    }
    if validator.object(
        item.get("evaluator"),
        file,
        &format!("{path}.evaluator"),
        &["type"],
    ) && item
        .get("evaluator")
        .and_then(|value| value.get("type"))
        .and_then(Value::as_str)
        != Some("self_review")
    {
        validator.error(
            "AXC302",
            file,
            &format!("{path}.evaluator.type"),
            "Proof evaluator must be self_review.",
        );
    }
    let objective_id = validator.id(
        item.get("objective_id"),
        file,
        &format!("{path}.objective_id"),
    );
    if let Some(objective_id) = objective_id {
        match objectives.get(&objective_id) {
            Some(o)
                if !matches!(
                    o.skill.as_str(),
                    "proof_construction" | "proof_comprehension"
                ) =>
            {
                validator.error(
                    "AXC302",
                    file,
                    &format!("{path}.objective_id"),
                    "Proof self-review requires a proof objective.",
                )
            }
            None => validator.error(
                "AXC203",
                file,
                &format!("{path}.objective_id"),
                format!("Unknown objective '{objective_id}'."),
            ),
            _ => (),
        }
    }
}
