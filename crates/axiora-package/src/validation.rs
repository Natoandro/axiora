use super::*;

#[derive(Default)]
struct Validator {
    diagnostics: Vec<Diagnostic>,
}

impl Validator {
    fn error(&mut self, code: &str, file: &str, path: &str, message: impl Into<String>) {
        self.error_with_suggestion(code, file, path, message, None);
    }

    fn error_with_suggestion(
        &mut self,
        code: &str,
        file: &str,
        path: &str,
        message: impl Into<String>,
        suggestion: Option<String>,
    ) {
        self.diagnostics.push(Diagnostic {
            code: code.into(),
            severity: "error".into(),
            file: file.into(),
            path: path.into(),
            message: message.into(),
            suggestion,
        });
    }

    fn warning(&mut self, code: &str, file: &str, path: &str, message: impl Into<String>) {
        self.diagnostics.push(Diagnostic {
            code: code.into(),
            severity: "warning".into(),
            file: file.into(),
            path: path.into(),
            message: message.into(),
            suggestion: None,
        });
    }

    fn object(&mut self, value: Option<&Value>, file: &str, path: &str, required: &[&str]) -> bool {
        let Some(Value::Object(object)) = value else {
            self.error("AXC110", file, path, "Expected an object.");
            return false;
        };
        for key in required {
            if !object.contains_key(*key) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Required field '{key}' is missing."),
                );
            }
        }
        for key in object.keys() {
            if !required.contains(&key.as_str()) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Unknown field '{key}'."),
                );
            }
        }
        true
    }

    fn object_optional(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        required: &[&str],
        optional: &[&str],
    ) -> bool {
        let Some(Value::Object(object)) = value else {
            self.error("AXC110", file, path, "Expected an object.");
            return false;
        };
        for key in required {
            if !object.contains_key(*key) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Required field '{key}' is missing."),
                );
            }
        }
        for key in object.keys() {
            if !required.contains(&key.as_str()) && !optional.contains(&key.as_str()) {
                self.error(
                    "AXC110",
                    file,
                    &format!("{path}.{key}"),
                    format!("Unknown field '{key}'."),
                );
            }
        }
        true
    }

    fn string(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        max: usize,
    ) -> Option<String> {
        match value {
            Some(Value::String(value))
                if !value.trim().is_empty() && value.chars().count() <= max =>
            {
                Some(value.clone())
            }
            _ => {
                self.error(
                    "AXC110",
                    file,
                    path,
                    format!("Expected a nonempty string of at most {max} characters."),
                );
                None
            }
        }
    }

    fn id(&mut self, value: Option<&Value>, file: &str, path: &str) -> Option<String> {
        let value = self.string(value, file, path, 120)?;
        if valid_id(&value) {
            Some(value)
        } else {
            self.error(
                "AXC201",
                file,
                path,
                format!("Invalid stable ID '{value}'."),
            );
            None
        }
    }

    fn revision(&mut self, value: Option<&Value>, file: &str, path: &str) -> Option<String> {
        let value = self.string(value, file, path, 32)?;
        if valid_revision(&value) {
            Some(value)
        } else {
            self.error(
                "AXC101",
                file,
                path,
                "Expected a three-part numeric version.",
            );
            None
        }
    }

    fn string_array(
        &mut self,
        value: Option<&Value>,
        file: &str,
        path: &str,
        nonempty: bool,
    ) -> Vec<String> {
        let Some(Value::Array(values)) = value else {
            self.error(
                "AXC110",
                file,
                path,
                format!(
                    "Expected {}array of strings.",
                    if nonempty { "a nonempty " } else { "an " }
                ),
            );
            return vec![];
        };
        if nonempty && values.is_empty() {
            self.error(
                "AXC110",
                file,
                path,
                "Expected a nonempty array of strings.",
            );
        }
        let mut strings = Vec::new();
        for (index, value) in values.iter().enumerate() {
            if let Some(value) = self.string(Some(value), file, &format!("{path}[{index}]"), 120) {
                strings.push(value);
            }
        }
        if strings.iter().collect::<HashSet<_>>().len() != strings.len() {
            self.error("AXC110", file, path, "Array values must be unique.");
        }
        strings
    }

    fn read_json(&mut self, root: &Path, relative: &str, limit: u64) -> Option<Value> {
        if !safe_relative_path(relative) {
            self.error("AXC103", relative, "$", "Unsafe relative path.");
            return None;
        }
        let path = root.join(relative);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => metadata,
            _ => {
                self.error(
                    "AXC103",
                    relative,
                    "$",
                    "File is missing or is not a regular file.",
                );
                return None;
            }
        };
        if metadata.len() > limit {
            self.error(
                "AXC103",
                relative,
                "$",
                format!("File exceeds the {limit}-byte limit."),
            );
            return None;
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        let file = match fs::File::open(&path) {
            Ok(file) => file,
            Err(error) => {
                self.error(
                    "AXC103",
                    relative,
                    "$",
                    format!("Cannot read file: {error}"),
                );
                return None;
            }
        };
        if file.take(limit + 1).read_to_end(&mut bytes).is_err() || bytes.len() as u64 > limit {
            self.error(
                "AXC103",
                relative,
                "$",
                format!("File exceeds the {limit}-byte limit."),
            );
            return None;
        }
        match parse_json_strict(&bytes, relative) {
            Ok(value) => Some(value),
            Err(diagnostic) => {
                self.diagnostics.push(diagnostic);
                None
            }
        }
    }
}

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
    let units = parse_manifest_units(&mut validator, manifest.get("units"));
    let files = parse_files(&mut validator, manifest.get("files"));
    parse_review(&mut validator, manifest.get("review"));
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
        if let Some((actual_id, actual_version)) = validate_unit(
            &mut validator,
            &document,
            path,
            &concept_map,
            &mut used_capabilities,
        ) {
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

fn parse_manifest_units(
    validator: &mut Validator,
    value: Option<&Value>,
) -> Vec<(String, String, String)> {
    let Some(Value::Array(records)) = value else {
        validator.error(
            "AXC110",
            "manifest.json",
            "$.units",
            "Expected a nonempty unit array.",
        );
        return vec![];
    };
    if records.is_empty() {
        validator.error(
            "AXC110",
            "manifest.json",
            "$.units",
            "Expected a nonempty unit array.",
        );
    }
    let mut result = vec![];
    for (index, record) in records.iter().enumerate() {
        let path = format!("$.units[{index}]");
        if !validator.object(
            Some(record),
            "manifest.json",
            &path,
            &["id", "version", "path"],
        ) {
            continue;
        }
        let id = validator.id(record.get("id"), "manifest.json", &format!("{path}.id"));
        let version = validator.revision(
            record.get("version"),
            "manifest.json",
            &format!("{path}.version"),
        );
        let payload = validator.string(
            record.get("path"),
            "manifest.json",
            &format!("{path}.path"),
            240,
        );
        if let Some(payload) = &payload {
            if !safe_relative_path(payload) {
                validator.error(
                    "AXC103",
                    "manifest.json",
                    &format!("{path}.path"),
                    format!("Unsafe package path '{payload}'."),
                );
            }
        }
        if let (Some(id), Some(version), Some(payload)) = (id, version, payload) {
            result.push((id, version, payload));
        }
    }
    result
}

fn parse_files(validator: &mut Validator, value: Option<&Value>) -> Vec<(String, u64, String)> {
    let Some(Value::Array(records)) = value else {
        validator.error(
            "AXC110",
            "manifest.json",
            "$.files",
            "Expected a nonempty file array.",
        );
        return vec![];
    };
    if records.is_empty() {
        validator.error(
            "AXC110",
            "manifest.json",
            "$.files",
            "Expected a nonempty file array.",
        );
    }
    let mut result = vec![];
    for (index, record) in records.iter().enumerate() {
        let path = format!("$.files[{index}]");
        if !validator.object(
            Some(record),
            "manifest.json",
            &path,
            &["path", "bytes", "sha256"],
        ) {
            continue;
        }
        let file = validator.string(
            record.get("path"),
            "manifest.json",
            &format!("{path}.path"),
            240,
        );
        let bytes = match record.get("bytes").and_then(Value::as_u64) {
            Some(bytes) => Some(bytes),
            None => {
                validator.error(
                    "AXC102",
                    "manifest.json",
                    &format!("{path}.bytes"),
                    "Expected a nonnegative integer.",
                );
                None
            }
        };
        let hash = validator.string(
            record.get("sha256"),
            "manifest.json",
            &format!("{path}.sha256"),
            64,
        );
        if let Some(hash) = &hash {
            if hash.len() != 64
                || !hash
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
            {
                validator.error(
                    "AXC110",
                    "manifest.json",
                    &format!("{path}.sha256"),
                    "Expected lowercase SHA-256 hex.",
                );
            }
        }
        if let (Some(file), Some(bytes), Some(hash)) = (file, bytes, hash) {
            result.push((file, bytes, hash));
        }
    }
    result
}

fn parse_review(validator: &mut Validator, value: Option<&Value>) {
    if !validator.object(value, "manifest.json", "$.review", &["status", "note"]) {
        return;
    }
    let status = validator.string(
        value.and_then(|v| v.get("status")),
        "manifest.json",
        "$.review.status",
        40,
    );
    validator.string(
        value.and_then(|v| v.get("note")),
        "manifest.json",
        "$.review.note",
        100_000,
    );
    if !matches!(
        status.as_deref(),
        Some("draft" | "mathematically_reviewed" | "pilot_reviewed")
    ) {
        validator.error(
            "AXC110",
            "manifest.json",
            "$.review.status",
            "Unsupported review status.",
        );
    }
    if matches!(
        status.as_deref(),
        Some("mathematically_reviewed" | "pilot_reviewed")
    ) {
        validator.warning(
            "AXC501",
            "manifest.json",
            "$.review",
            "This author-declared review status is not authenticated by the validator.",
        );
    }
}

fn collect_payload_files(root: &Path) -> BTreeSet<String> {
    fn visit(root: &Path, dir: &Path, result: &mut BTreeSet<String>) {
        let Ok(entries) = fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(metadata) = fs::symlink_metadata(&path) else {
                continue;
            };
            if metadata.file_type().is_symlink() {
                if fs::metadata(&path).is_ok_and(|target| target.is_file())
                    && path != root.join("README.md")
                {
                    if let Ok(relative) = path.strip_prefix(root) {
                        result.insert(relative.to_string_lossy().replace('\\', "/"));
                    }
                }
                continue;
            }
            if metadata.is_dir() {
                visit(root, &path, result);
            } else if metadata.is_file()
                && path != root.join("manifest.json")
                && path != root.join("README.md")
            {
                if let Ok(relative) = path.strip_prefix(root) {
                    result.insert(relative.to_string_lossy().replace('\\', "/"));
                }
            }
        }
    }
    let mut result = BTreeSet::new();
    visit(root, root, &mut result);
    result
}

mod concepts;
mod units;

use concepts::{parse_concept_document, validate_concept_relations};
use units::validate_unit;

#[cfg(test)]
#[path = "validation_tests.rs"]
mod tests;
