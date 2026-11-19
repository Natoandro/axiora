use super::*;

pub(super) fn parse_objectives(
    validator: &mut Validator,
    value: Option<&Value>,
    file: &str,
    concepts: &HashSet<String>,
) -> BTreeMap<String, Objective> {
    let Some(Value::Array(values)) = value else {
        validator.error("AXC110", file, "$.objectives", "Expected a nonempty array.");
        return BTreeMap::new();
    };
    if values.is_empty() {
        validator.error("AXC110", file, "$.objectives", "Expected a nonempty array.");
    }
    let mut result = BTreeMap::new();
    for (i, item) in values.iter().enumerate() {
        let path = format!("$.objectives[{i}]");
        if !validator.object(
            Some(item),
            file,
            &path,
            &["id", "concept_id", "skill", "description"],
        ) {
            continue;
        }
        let id = validator.id(item.get("id"), file, &format!("{path}.id"));
        let concept = validator.id(item.get("concept_id"), file, &format!("{path}.concept_id"));
        let skill = validator.string(item.get("skill"), file, &format!("{path}.skill"), 64);
        validator.string(
            item.get("description"),
            file,
            &format!("{path}.description"),
            100_000,
        );
        if let Some(skill) = &skill {
            if !matches!(
                skill.as_str(),
                "recall"
                    | "recognition"
                    | "application"
                    | "proof_comprehension"
                    | "proof_construction"
                    | "transfer"
            ) {
                validator.error(
                    "AXC302",
                    file,
                    &format!("{path}.skill"),
                    format!("Unsupported objective skill '{skill}'."),
                );
            }
        }
        if let Some(concept) = &concept {
            if !concepts.contains(concept) {
                validator.error(
                    "AXC203",
                    file,
                    &format!("{path}.concept_id"),
                    "Objective concept is not listed in concept_ids.",
                );
            }
        }
        if let (Some(id), Some(skill)) = (id, skill) {
            if result.insert(id.clone(), Objective { skill }).is_some() {
                validator.error(
                    "AXC203",
                    file,
                    "$.objectives",
                    format!("Duplicate objective ID '{id}'."),
                );
            }
        }
    }
    result
}

pub(super) fn parse_sources(
    validator: &mut Validator,
    value: Option<&Value>,
    file: &str,
) -> HashSet<String> {
    let Some(Value::Array(values)) = value else {
        validator.error("AXC110", file, "$.sources", "Expected a nonempty array.");
        return HashSet::new();
    };
    if values.is_empty() {
        validator.error("AXC110", file, "$.sources", "Expected a nonempty array.");
    }
    let mut result = HashSet::new();
    for (i, item) in values.iter().enumerate() {
        let path = format!("$.sources[{i}]");
        if !validator.object(
            Some(item),
            file,
            &path,
            &["id", "kind", "citation", "locator", "rights"],
        ) {
            continue;
        }
        let id = validator.id(item.get("id"), file, &format!("{path}.id"));
        let kind = validator.string(item.get("kind"), file, &format!("{path}.kind"), 32);
        for key in ["citation", "locator", "rights"] {
            validator.string(item.get(key), file, &format!("{path}.{key}"), 100_000);
        }
        if !matches!(kind.as_deref(), Some("original" | "reference")) {
            validator.error(
                "AXC110",
                file,
                &format!("{path}.kind"),
                "Source kind must be original or reference.",
            );
        }
        if let Some(id) = id {
            if !result.insert(id.clone()) {
                validator.error(
                    "AXC203",
                    file,
                    &format!("{path}.id"),
                    format!("Duplicate source ID '{id}'."),
                );
            }
        }
    }
    result
}
