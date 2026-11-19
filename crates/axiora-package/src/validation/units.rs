use super::*;

pub(super) struct UnitValidator<'a> {
    pub(super) validator: &'a mut Validator,
    pub(super) file: &'a str,
    pub(super) concepts: &'a BTreeMap<String, Vec<(String, String)>>,
    pub(super) used_capabilities: &'a mut HashSet<String>,
}

impl UnitValidator<'_> {
    pub(super) fn validate(&mut self, document: &Value) -> Option<(String, String)> {
        let validator = &mut *self.validator;
        let file = self.file;
        let concepts = self.concepts;
        if !validator.object(
            Some(document),
            file,
            "$",
            &[
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
            ],
        ) {
            return None;
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
        let id = validator.id(document.get("id"), file, "$.id");
        let version = validator.revision(document.get("version"), file, "$.version");
        validator.string(document.get("title"), file, "$.title", 200);
        let concept_ids =
            validator.string_array(document.get("concept_ids"), file, "$.concept_ids", true);
        let concept_ids = concept_ids
            .into_iter()
            .filter(|id| {
                if !valid_id(id) {
                    validator.error(
                        "AXC201",
                        file,
                        "$.concept_ids",
                        format!("Invalid stable ID '{id}'."),
                    );
                    false
                } else {
                    true
                }
            })
            .collect::<HashSet<_>>();
        let required = validator.string_array(
            document.get("requires_concepts"),
            file,
            "$.requires_concepts",
            false,
        );
        for id in required {
            if !valid_id(&id) {
                validator.error(
                    "AXC201",
                    file,
                    "$.requires_concepts",
                    format!("Invalid stable ID '{id}'."),
                );
            }
        }
        for id in &concept_ids {
            if !concepts.contains_key(id) {
                validator.error(
                    "AXC203",
                    file,
                    "$.concept_ids",
                    format!("Concept '{id}' is not defined in this package."),
                );
            }
        }
        let objectives =
            parse_objectives(validator, document.get("objectives"), file, &concept_ids);
        let sources = parse_sources(validator, document.get("sources"), file);
        let entry = validator.id(document.get("entry"), file, "$.entry");
        let activities = self.parse_activities(document.get("activities"), &sources, &objectives);
        validate_activity_graph(
            self.validator,
            file,
            entry.as_deref().unwrap_or(""),
            &activities,
        );
        Some((id?, version?))
    }
}

#[derive(Default, Clone)]
struct Objective {
    skill: String,
}
#[derive(Clone)]
struct Activity {
    order: usize,
    kind: String,
    targets: Vec<String>,
}

mod metadata;
use metadata::{parse_objectives, parse_sources};

impl UnitValidator<'_> {
    fn parse_activities(
        &mut self,
        value: Option<&Value>,
        sources: &HashSet<String>,
        objectives: &BTreeMap<String, Objective>,
    ) -> BTreeMap<String, Activity> {
        let validator = &mut *self.validator;
        let file = self.file;
        let used = &mut *self.used_capabilities;
        let Some(Value::Object(values)) = value else {
            validator.error(
                "AXC110",
                file,
                "$.activities",
                "Expected a nonempty activity object.",
            );
            return BTreeMap::new();
        };
        if values.is_empty() {
            validator.error(
                "AXC110",
                file,
                "$.activities",
                "Expected a nonempty activity object.",
            );
        }
        let mut result = BTreeMap::new();
        for (order, (id, item)) in values.iter().enumerate() {
            if !valid_id(id) {
                validator.error(
                    "AXC201",
                    file,
                    &format!("$.activities.{id}"),
                    format!("Invalid stable ID '{id}'."),
                );
            }
            let Some(kind) = item.get("type").and_then(Value::as_str) else {
                validator.error(
                    "AXC110",
                    file,
                    &format!("$.activities.{id}.type"),
                    "Expected a nonempty string.",
                );
                continue;
            };
            let Some(source_ids) = item.get("source_ids") else {
                validator.error(
                    "AXC110",
                    file,
                    &format!("$.activities.{id}.source_ids"),
                    "Expected a nonempty array of strings.",
                );
                continue;
            };
            let mut scratch = Validator::default();
            let source_ids = scratch.string_array(
                Some(source_ids),
                file,
                &format!("$.activities.{id}.source_ids"),
                true,
            );
            validator.diagnostics.extend(scratch.diagnostics);
            for source in source_ids {
                if !sources.contains(&source) {
                    validator.error(
                        "AXC203",
                        file,
                        &format!("$.activities.{id}.source_ids"),
                        format!("Unknown source '{source}'."),
                    );
                }
            }
            let targets = match kind {
                "exposition" => {
                    used.insert("activity.exposition.v1".into());
                    required_fields(
                        validator,
                        item,
                        file,
                        id,
                        &["content_md", "next", "source_ids", "type"],
                        &[],
                    );
                    nonempty(
                        item.get("content_md"),
                        validator,
                        file,
                        &format!("$.activities.{id}.content_md"),
                    );
                    one_target(item.get("next"), validator, file, id)
                }
                "choice" => {
                    used.insert("activity.choice.v1".into());
                    used.insert("evaluator.exact-choice.v1".into());
                    parse_choice(validator, item, file, id, objectives);
                    choice_targets(item.get("transitions"), validator, file, id)
                }
                "proof" => {
                    used.insert("activity.proof.v1".into());
                    used.insert("evaluator.self-review.v1".into());
                    parse_proof(validator, item, file, id, objectives);
                    one_target(item.get("next"), validator, file, id)
                }
                "reflection" => {
                    used.insert("activity.reflection.v1".into());
                    required_fields(
                        validator,
                        item,
                        file,
                        id,
                        &["type", "source_ids", "prompt_md", "next"],
                        &[],
                    );
                    nonempty(
                        item.get("prompt_md"),
                        validator,
                        file,
                        &format!("$.activities.{id}.prompt_md"),
                    );
                    one_target(item.get("next"), validator, file, id)
                }
                "end" => {
                    used.insert("activity.end.v1".into());
                    required_fields(
                        validator,
                        item,
                        file,
                        id,
                        &["type", "source_ids", "summary_md"],
                        &[],
                    );
                    nonempty(
                        item.get("summary_md"),
                        validator,
                        file,
                        &format!("$.activities.{id}.summary_md"),
                    );
                    vec![]
                }
                _ => {
                    validator.error(
                        "AXC102",
                        file,
                        &format!("$.activities.{id}.type"),
                        "Unknown activity type.",
                    );
                    vec![]
                }
            };
            result.insert(
                id.clone(),
                Activity {
                    order,
                    kind: kind.into(),
                    targets,
                },
            );
        }
        result
    }
}

fn required_fields(
    validator: &mut Validator,
    item: &Value,
    file: &str,
    id: &str,
    keys: &[&str],
    optional: &[&str],
) {
    validator.object_optional(
        Some(item),
        file,
        &format!("$.activities.{id}"),
        keys,
        optional,
    );
}
fn nonempty(value: Option<&Value>, validator: &mut Validator, file: &str, path: &str) {
    validator.string(value, file, path, 100_000);
}
fn one_target(
    value: Option<&Value>,
    validator: &mut Validator,
    file: &str,
    id: &str,
) -> Vec<String> {
    validator
        .id(value, file, &format!("$.activities.{id}.next"))
        .into_iter()
        .collect()
}
fn choice_targets(
    value: Option<&Value>,
    validator: &mut Validator,
    file: &str,
    id: &str,
) -> Vec<String> {
    let mut result = vec![];
    if validator.object(
        value,
        file,
        &format!("$.activities.{id}.transitions"),
        &["correct", "incorrect"],
    ) {
        for key in ["correct", "incorrect"] {
            if let Some(target) = validator.id(
                value.and_then(|v| v.get(key)),
                file,
                &format!("$.activities.{id}.transitions.{key}"),
            ) {
                result.push(target);
            }
        }
    }
    result
}

mod answers;
use answers::{parse_choice, parse_proof};

mod graph;
use graph::validate_activity_graph;
