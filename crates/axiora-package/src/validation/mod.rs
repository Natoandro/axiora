use super::*;

mod validator;
use validator::Validator;

pub fn validate_directory(root: &Path) -> ValidationResult {
    let mut validator = Validator::default();
    let root = match fs::canonicalize(root) {
        Ok(root) if root.is_dir() => root,
        _ => {
            validator.error("AXC100", "", "$", "Input is not a directory.");
            return result(validator.diagnostics);
        }
    };
    let Some(manifest) = validator.read_json(&root, "manifest.json", MANIFEST_BYTES) else {
        return result(validator.diagnostics);
    };
    if !validator.object(
        Some(&manifest),
        "manifest.json",
        "$",
        &[
            "schema_version",
            "package_id",
            "version",
            "title",
            "language",
            "license",
            "requires_capabilities",
            "conventions",
            "concept_files",
            "units",
            "entry_units",
            "review",
            "files",
        ],
    ) {
        return result(validator.diagnostics);
    }
    let schema = validator.revision(
        manifest.get("schema_version"),
        "manifest.json",
        "$.schema_version",
    );
    if schema.as_deref().is_some_and(|v| v != "0.1.0") {
        validator.error_with_suggestion(
            "AXC101",
            "manifest.json",
            "$.schema_version",
            format!("Unsupported schema version '{}'.", schema.unwrap()),
            Some("This validator supports 0.1.0.".into()),
        );
    }
    validator.id(manifest.get("package_id"), "manifest.json", "$.package_id");
    validator.revision(manifest.get("version"), "manifest.json", "$.version");
    validator.string(manifest.get("title"), "manifest.json", "$.title", 200);
    validator.string(manifest.get("language"), "manifest.json", "$.language", 64);
    validator.string(manifest.get("license"), "manifest.json", "$.license", 200);
    validator.string(
        manifest.get("conventions"),
        "manifest.json",
        "$.conventions",
        100_000,
    );

    let capabilities = validator.string_array(
        manifest.get("requires_capabilities"),
        "manifest.json",
        "$.requires_capabilities",
        false,
    );
    const SUPPORTED: &[&str] = &[
        "activity.exposition.v1",
        "activity.choice.v1",
        "activity.proof.v1",
        "activity.reflection.v1",
        "activity.end.v1",
        "evaluator.exact-choice.v1",
        "evaluator.self-review.v1",
    ];
    for capability in &capabilities {
        if !SUPPORTED.contains(&capability.as_str()) {
            validator.error(
                "AXC102",
                "manifest.json",
                "$.requires_capabilities",
                format!("Unsupported required capability '{capability}'."),
            );
        }
    }
    let concepts = validator.string_array(
        manifest.get("concept_files"),
        "manifest.json",
        "$.concept_files",
        true,
    );
    let entry_units = validator.string_array(
        manifest.get("entry_units"),
        "manifest.json",
        "$.entry_units",
        true,
    );
    for (i, id) in entry_units.iter().enumerate() {
        if !valid_id(id) {
            validator.error(
                "AXC201",
                "manifest.json",
                &format!("$.entry_units[{i}]"),
                format!("Invalid stable ID '{id}'."),
            );
        }
    }
    let mut manifest_validator = ManifestValidator {
        validator: &mut validator,
    };
    let units = manifest_validator.parse_manifest_units(manifest.get("units"));
    let files = manifest_validator.parse_files(manifest.get("files"));
    manifest_validator.parse_review(manifest.get("review"));
    duplicate_values(
        &mut validator,
        &concepts,
        "manifest.json",
        "$.concept_files",
        "Concept path",
    );
    duplicate_values(
        &mut validator,
        &units.iter().map(|u| u.0.clone()).collect::<Vec<_>>(),
        "manifest.json",
        "$.units",
        "Unit ID",
    );
    duplicate_values(
        &mut validator,
        &units.iter().map(|u| u.2.clone()).collect::<Vec<_>>(),
        "manifest.json",
        "$.units",
        "Unit path",
    );
    duplicate_values(
        &mut validator,
        &files.iter().map(|f| f.0.clone()).collect::<Vec<_>>(),
        "manifest.json",
        "$.files",
        "File path",
    );
    let folded = files.iter().map(|f| f.0.to_lowercase()).collect::<Vec<_>>();
    if folded.iter().collect::<HashSet<_>>().len() != folded.len() {
        validator.error(
            "AXC103",
            "manifest.json",
            "$.files",
            "Payload paths must not collide case-insensitively.",
        );
    }
    let owned = concepts
        .iter()
        .cloned()
        .chain(units.iter().map(|u| u.2.clone()))
        .collect::<Vec<_>>();
    duplicate_values(
        &mut validator,
        &owned,
        "manifest.json",
        "$.files",
        "Payload ownership",
    );
    let declared = files.iter().map(|f| f.0.clone()).collect::<BTreeSet<_>>();
    for path in &owned {
        if !declared.contains(path) {
            validator.error(
                "AXC203",
                "manifest.json",
                "$.files",
                format!("Referenced payload '{path}' is missing from the file inventory."),
            );
        }
    }
    let actual = collect_payload_files(&root);
    for path in actual.difference(&declared) {
        validator.error(
            "AXC203",
            path,
            "$",
            "File is not declared in manifest inventory.",
        );
    }
    for path in declared.difference(&actual) {
        validator.error(
            "AXC203",
            "manifest.json",
            "$.files",
            format!("Declared payload '{path}' does not exist."),
        );
    }

    let mut concept_map = BTreeMap::<String, Vec<(String, String)>>::new();
    for path in &concepts {
        if let Some(document) = validator.read_json(&root, path, PAYLOAD_BYTES) {
            parse_concept_document(&mut validator, &document, path, &mut concept_map);
        }
    }
    validate_concept_relations(&mut validator, &concept_map);

    let mut seen_unit_ids = HashSet::new();
    let mut used_capabilities = HashSet::new();
    for (expected_id, expected_version, path) in &units {
        let Some(document) = validator.read_json(&root, path, PAYLOAD_BYTES) else {
            continue;
        };
        if let Some((actual_id, actual_version)) = (UnitValidator {
            validator: &mut validator,
            file: path,
            concepts: &concept_map,
            used_capabilities: &mut used_capabilities,
        })
        .validate(&document)
        {
            if !seen_unit_ids.insert(actual_id.clone()) {
                validator.error(
                    "AXC203",
                    path,
                    "$.id",
                    format!("Duplicate unit ID '{actual_id}'."),
                );
            }
            if &actual_id != expected_id || &actual_version != expected_version {
                validator.error(
                    "AXC203",
                    path,
                    "$.id",
                    "Unit identity does not match its manifest record.",
                );
            }
        }
    }
    if !seen_unit_ids.is_empty() && seen_unit_ids != units.iter().map(|u| u.0.clone()).collect() {
        validator.error(
            "AXC203",
            "manifest.json",
            "$.units",
            "Manifest unit records do not match readable unit payloads.",
        );
    }
    let unit_ids = units.iter().map(|u| u.0.as_str()).collect::<HashSet<_>>();
    for id in &entry_units {
        if !unit_ids.contains(id.as_str()) {
            validator.error(
                "AXC203",
                "manifest.json",
                "$.entry_units",
                format!("Entry unit '{id}' is not declared."),
            );
        }
    }
    for capability in used_capabilities.difference(&capabilities.iter().cloned().collect()) {
        validator.error(
            "AXC102",
            "manifest.json",
            "$.requires_capabilities",
            format!("Manifest omits required capability '{capability}'."),
        );
    }

    for (path, declared_bytes, expected_hash) in files {
        if !safe_relative_path(&path) {
            validator.error(
                "AXC103",
                "manifest.json",
                "$.files",
                format!("Unsafe package path '{path}'."),
            );
            continue;
        }
        let target = root.join(&path);
        let Ok(metadata) = fs::symlink_metadata(&target) else {
            continue;
        };
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            continue;
        }
        let mut bytes = Vec::new();
        let file = match fs::File::open(&target) {
            Ok(file) => file,
            Err(_) => continue,
        };
        if file
            .take(PAYLOAD_BYTES + 1)
            .read_to_end(&mut bytes)
            .is_err()
        {
            continue;
        }
        if bytes.len() as u64 != declared_bytes {
            validator.error(
                "AXC103",
                &path,
                "$",
                format!(
                    "Byte count is {}; manifest declares {declared_bytes}.",
                    bytes.len()
                ),
            );
        }
        if sha256(&bytes) != expected_hash {
            validator.error("AXC103", &path, "$", "SHA-256 does not match the manifest.");
        }
    }
    result(validator.diagnostics)
}

fn result(diagnostics: Vec<Diagnostic>) -> ValidationResult {
    let valid = !diagnostics
        .iter()
        .any(|diagnostic| diagnostic.severity == "error");
    ValidationResult { diagnostics, valid }
}

fn valid_id(value: &str) -> bool {
    if value.is_empty() || value.len() > 120 || !value.as_bytes()[0].is_ascii_lowercase() {
        return false;
    }
    let mut previous_separator = true;
    for byte in value.bytes() {
        match byte {
            b'a'..=b'z' | b'0'..=b'9' => previous_separator = false,
            b'.' | b'-' if !previous_separator => previous_separator = true,
            _ => return false,
        }
    }
    !previous_separator
}

fn valid_revision(value: &str) -> bool {
    let parts = value.split('.').collect::<Vec<_>>();
    parts.len() == 3
        && parts
            .iter()
            .all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
}

pub(crate) fn safe_relative_path(value: &str) -> bool {
    if value.is_empty() || value.starts_with('/') || value.contains('\\') || value.contains('\0') {
        return false;
    }
    let path = Path::new(value);
    !path.is_absolute()
        && path
            .components()
            .all(|component| matches!(component, Component::Normal(_)))
        && value
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

fn duplicate_values(
    validator: &mut Validator,
    values: &[String],
    file: &str,
    path: &str,
    label: &str,
) {
    let mut counts = BTreeMap::new();
    for value in values {
        *counts.entry(value).or_insert(0usize) += 1;
    }
    for (value, count) in counts {
        if count > 1 {
            validator.error(
                "AXC203",
                file,
                path,
                format!("{label} '{value}' is declared more than once."),
            );
        }
    }
}

mod manifest;
use manifest::{ManifestValidator, collect_payload_files};

mod concepts;
mod units;

use concepts::{parse_concept_document, validate_concept_relations};
use units::UnitValidator;

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
