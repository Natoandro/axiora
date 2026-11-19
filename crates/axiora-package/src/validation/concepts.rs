use super::*;

pub(super) fn parse_concept_document(
    validator: &mut Validator,
    document: &Value,
    file: &str,
    concepts: &mut BTreeMap<String, Vec<(String, String)>>,
) {
    if !validator.object(Some(document), file, "$", &["schema_version", "concepts"]) {
        return;
    }
    let schema = validator.revision(document.get("schema_version"), file, "$.schema_version");
    if schema.as_deref() != Some("0.1.0") {
        validator.error(
            "AXC101",
            file,
            "$.schema_version",
            "Unsupported schema version.",
        );
    }
    let Some(Value::Array(items)) = document.get("concepts") else {
        validator.error(
            "AXC110",
            file,
            "$.concepts",
            "Expected a nonempty concept array.",
        );
        return;
    };
    if items.is_empty() {
        validator.error(
            "AXC110",
            file,
            "$.concepts",
            "Expected a nonempty concept array.",
        );
    }
    for (index, item) in items.iter().enumerate() {
        let path = format!("$.concepts[{index}]");
        if !validator.object(
            Some(item),
            file,
            &path,
            &["id", "title", "definition_md", "assumptions", "relations"],
        ) {
            continue;
        }
        let Some(id) = validator.id(item.get("id"), file, &format!("{path}.id")) else {
            continue;
        };
        validator.string(item.get("title"), file, &format!("{path}.title"), 200);
        validator.string(
            item.get("definition_md"),
            file,
            &format!("{path}.definition_md"),
            100_000,
        );
        validator.string(
            item.get("assumptions"),
            file,
            &format!("{path}.assumptions"),
            100_000,
        );
        let mut relations = vec![];
        match item.get("relations") {
            Some(Value::Array(items)) => {
                for (index, relation) in items.iter().enumerate() {
                    let rel_path = format!("{path}.relations[{index}]");
                    if !validator.object(Some(relation), file, &rel_path, &["type", "target"]) {
                        continue;
                    }
                    let kind = validator.string(
                        relation.get("type"),
                        file,
                        &format!("{rel_path}.type"),
                        32,
                    );
                    let target =
                        validator.id(relation.get("target"), file, &format!("{rel_path}.target"));
                    if !matches!(
                        kind.as_deref(),
                        Some("prerequisite" | "implies" | "equivalent" | "generalizes")
                    ) {
                        validator.error(
                            "AXC110",
                            file,
                            &format!("{rel_path}.type"),
                            "Unknown relation type.",
                        );
                    }
                    if let (Some(kind), Some(target)) = (kind, target) {
                        relations.push((kind, target));
                    }
                }
            }
            _ => validator.error(
                "AXC110",
                file,
                &format!("{path}.relations"),
                "Expected an array.",
            ),
        }
        if concepts.insert(id.clone(), relations).is_some() {
            validator.error(
                "AXC203",
                file,
                &format!("{path}.id"),
                format!("Duplicate concept ID '{id}'."),
            );
        }
    }
}

pub(super) fn validate_concept_relations(
    validator: &mut Validator,
    concepts: &BTreeMap<String, Vec<(String, String)>>,
) {
    for (id, relations) in concepts {
        for (_, target) in relations {
            if !concepts.contains_key(target) {
                validator.error(
                    "AXC203",
                    "concepts",
                    &format!("$.concepts.{id}.relations"),
                    format!("Unknown concept '{target}'."),
                );
            }
        }
    }
    fn visit(
        id: &str,
        concepts: &BTreeMap<String, Vec<(String, String)>>,
        visiting: &mut HashSet<String>,
        done: &mut HashSet<String>,
    ) -> bool {
        if visiting.contains(id) {
            return false;
        }
        if !done.insert(id.to_owned()) {
            return true;
        }
        visiting.insert(id.to_owned());
        let good = concepts
            .get(id)
            .into_iter()
            .flatten()
            .filter(|(kind, _)| kind == "prerequisite")
            .all(|(_, target)| {
                !concepts.contains_key(target) || visit(target, concepts, visiting, done)
            });
        visiting.remove(id);
        good
    }
    let mut visiting = HashSet::new();
    let mut done = HashSet::new();
    for id in concepts.keys() {
        if !visit(id, concepts, &mut visiting, &mut done) {
            validator.error(
                "AXC203",
                "concepts",
                "$.concepts",
                "Prerequisite relations contain a cycle.",
            );
            break;
        }
    }
}
