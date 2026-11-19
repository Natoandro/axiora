//! Rust package core for Axiora schema 0.1.0.
//!
//! The Kotlin implementation remains the compatibility reference while this crate is built out.

use serde::de::{MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::{Map, Number, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::fmt;
use std::fs;
use std::io::{self, Read};
use std::path::{Component, Path};

mod archive;
pub use archive::{
    BuildResult, LoadedPackage, inspect_archive, load_archive, pack_directory, write_loaded_package,
};

pub const ARCHIVE_BYTES: u64 = 20 * 1024 * 1024;
pub const TOTAL_BYTES: u64 = 50 * 1024 * 1024;
pub const FILES: usize = 500;
pub const PAYLOAD_BYTES: u64 = 2 * 1024 * 1024;
pub const MANIFEST_BYTES: u64 = 256 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    pub code: String,
    pub severity: String,
    pub file: String,
    pub path: String,
    pub message: String,
    pub suggestion: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationResult {
    pub diagnostics: Vec<Diagnostic>,
    pub valid: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageDescriptor {
    pub package_id: String,
    pub version: String,
    pub manifest_sha256: String,
    pub archive_sha256: String,
    pub payload_count: usize,
    pub uncompressed_bytes: u64,
}

#[derive(Debug)]
pub enum PackageError {
    Io(io::Error),
    Invalid(String),
}

impl fmt::Display for PackageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "{error}"),
            Self::Invalid(message) => write!(f, "{message}"),
        }
    }
}

impl std::error::Error for PackageError {}

impl From<io::Error> for PackageError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

pub fn sha256(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// Parse a JSON value while rejecting duplicate keys at every object depth.
pub fn parse_json_strict(bytes: &[u8], file: &str) -> Result<Value, Diagnostic> {
    let text = std::str::from_utf8(bytes).map_err(|error| Diagnostic {
        code: "AXC100".into(),
        severity: "error".into(),
        file: file.into(),
        path: "$".into(),
        message: format!("Invalid UTF-8 JSON: {error}."),
        suggestion: None,
    })?;
    let mut deserializer = serde_json::Deserializer::from_str(text);
    let value = StrictValue::deserialize(&mut deserializer).map_err(|error| {
        let duplicate = error.to_string().contains("duplicate object key");
        Diagnostic {
            code: if duplicate { "AXC104" } else { "AXC100" }.into(),
            severity: "error".into(),
            file: file.into(),
            path: "$".into(),
            message: if duplicate {
                "JSON object contains a duplicate key.".into()
            } else {
                format!("Invalid UTF-8 JSON: {}.", error)
            },
            suggestion: None,
        }
    })?;
    deserializer.end().map_err(|error| Diagnostic {
        code: "AXC100".into(),
        severity: "error".into(),
        file: file.into(),
        path: "$".into(),
        message: format!("Invalid UTF-8 JSON: {error}."),
        suggestion: None,
    })?;
    Ok(value.0)
}

pub fn parse_bounded_json(bytes: &[u8], file: &str) -> Result<Value, Diagnostic> {
    let value = parse_json_strict(bytes, file)?;
    fn depth(value: &Value) -> usize {
        match value {
            Value::Array(values) => 1 + values.iter().map(depth).max().unwrap_or(0),
            Value::Object(values) => 1 + values.values().map(depth).max().unwrap_or(0),
            _ => 0,
        }
    }
    if depth(&value) > 32 {
        return Err(Diagnostic {
            code: "AXC100".into(),
            severity: "error".into(),
            file: file.into(),
            path: "$".into(),
            message: format!("{file} exceeds JSON nesting limit."),
            suggestion: None,
        });
    }
    Ok(value)
}

struct StrictValue(Value);

impl<'de> Deserialize<'de> for StrictValue {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct StrictVisitor;
        impl<'de> Visitor<'de> for StrictVisitor {
            type Value = StrictValue;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("a JSON value")
            }

            fn visit_bool<E>(self, value: bool) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::Bool(value)))
            }
            fn visit_i64<E>(self, value: i64) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::Number(value.into())))
            }
            fn visit_u64<E>(self, value: u64) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::Number(value.into())))
            }
            fn visit_f64<E>(self, value: f64) -> Result<Self::Value, E>
            where
                E: serde::de::Error,
            {
                Number::from_f64(value)
                    .map(Value::Number)
                    .map(StrictValue)
                    .ok_or_else(|| E::custom("invalid JSON number"))
            }
            fn visit_str<E>(self, value: &str) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::String(value.into())))
            }
            fn visit_string<E>(self, value: String) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::String(value)))
            }
            fn visit_none<E>(self) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::Null))
            }
            fn visit_unit<E>(self) -> Result<Self::Value, E> {
                Ok(StrictValue(Value::Null))
            }
            fn visit_seq<A>(self, mut seq: A) -> Result<Self::Value, A::Error>
            where
                A: SeqAccess<'de>,
            {
                let mut values = Vec::new();
                while let Some(value) = seq.next_element::<StrictValue>()? {
                    values.push(value.0);
                }
                Ok(StrictValue(Value::Array(values)))
            }
            fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
            where
                A: MapAccess<'de>,
            {
                let mut values = Map::new();
                while let Some(key) = map.next_key::<String>()? {
                    if values.contains_key(&key) {
                        return Err(serde::de::Error::custom("duplicate object key"));
                    }
                    values.insert(key, map.next_value::<StrictValue>()?.0);
                }
                Ok(StrictValue(Value::Object(values)))
            }
        }
        deserializer.deserialize_any(StrictVisitor)
    }
}

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

fn safe_relative_path(value: &str) -> bool {
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

fn parse_concept_document(
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

fn validate_concept_relations(
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

fn validate_unit(
    validator: &mut Validator,
    document: &Value,
    file: &str,
    concepts: &BTreeMap<String, Vec<(String, String)>>,
    used_capabilities: &mut HashSet<String>,
) -> Option<(String, String)> {
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
    let objectives = parse_objectives(validator, document.get("objectives"), file, &concept_ids);
    let sources = parse_sources(validator, document.get("sources"), file);
    let entry = validator.id(document.get("entry"), file, "$.entry");
    let activities = parse_activities(
        validator,
        document.get("activities"),
        file,
        &sources,
        &objectives,
        used_capabilities,
    );
    validate_activity_graph(validator, file, entry.as_deref().unwrap_or(""), &activities);
    Some((id?, version?))
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

fn parse_objectives(
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

fn parse_sources(validator: &mut Validator, value: Option<&Value>, file: &str) -> HashSet<String> {
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

fn parse_activities(
    validator: &mut Validator,
    value: Option<&Value>,
    file: &str,
    sources: &HashSet<String>,
    objectives: &BTreeMap<String, Objective>,
    used: &mut HashSet<String>,
) -> BTreeMap<String, Activity> {
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

fn parse_choice(
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

fn parse_proof(
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

fn validate_activity_graph(
    validator: &mut Validator,
    file: &str,
    entry: &str,
    activities: &BTreeMap<String, Activity>,
) {
    if !activities.contains_key(entry) {
        validator.error("AXC203", file, "$.entry", "Entry activity does not exist.");
        return;
    }
    let mut ordered_ids = activities.keys().collect::<Vec<_>>();
    ordered_ids.sort_by_key(|id| activities[*id].order);
    for id in &ordered_ids {
        let activity = &activities[*id];
        for target in &activity.targets {
            if !activities.contains_key(target) {
                validator.error(
                    "AXC203",
                    file,
                    &format!("$.activities.{id}"),
                    format!("Activity target '{target}' does not exist."),
                );
            }
        }
    }
    fn reach(id: &str, graph: &BTreeMap<String, Activity>, reachable: &mut HashSet<String>) {
        if !reachable.insert(id.to_owned()) {
            return;
        }
        if let Some(activity) = graph.get(id) {
            for target in &activity.targets {
                if graph.contains_key(target) {
                    reach(target, graph, reachable);
                }
            }
        }
    }
    let mut reachable = HashSet::new();
    reach(entry, activities, &mut reachable);
    for id in &ordered_ids {
        if !reachable.contains(*id) {
            validator.error(
                "AXC203",
                file,
                &format!("$.activities.{id}"),
                "Activity is unreachable from entry.",
            );
        }
    }
    fn dfs(
        id: &str,
        graph: &BTreeMap<String, Activity>,
        visiting: &mut HashSet<String>,
        done: &mut HashSet<String>,
        cycles: &mut Vec<String>,
    ) {
        if visiting.contains(id) {
            cycles.push(id.to_owned());
            return;
        }
        if !done.insert(id.to_owned()) {
            return;
        }
        visiting.insert(id.to_owned());
        if let Some(activity) = graph.get(id) {
            for target in &activity.targets {
                if graph.contains_key(target) {
                    dfs(target, graph, visiting, done, cycles);
                }
            }
        }
        visiting.remove(id);
    }
    let mut visiting = HashSet::new();
    let mut done = HashSet::new();
    let mut cycles = Vec::new();
    for id in &ordered_ids {
        dfs(id, activities, &mut visiting, &mut done, &mut cycles);
    }
    for id in cycles {
        validator.error(
            "AXC203",
            file,
            &format!("$.activities.{id}"),
            "Activity graph contains a cycle.",
        );
    }
    fn terminates(
        id: &str,
        graph: &BTreeMap<String, Activity>,
        memo: &mut BTreeMap<String, bool>,
        checking: &mut HashSet<String>,
    ) -> bool {
        if let Some(value) = memo.get(id) {
            return *value;
        }
        if !checking.insert(id.to_owned()) {
            return false;
        }
        let Some(activity) = graph.get(id) else {
            checking.remove(id);
            return false;
        };
        let result = if activity.kind == "end" {
            true
        } else {
            !activity.targets.is_empty()
                && activity
                    .targets
                    .iter()
                    .all(|target| terminates(target, graph, memo, checking))
        };
        checking.remove(id);
        memo.insert(id.to_owned(), result);
        result
    }
    let mut memo = BTreeMap::new();
    let mut checking = HashSet::new();
    for id in &ordered_ids {
        if !terminates(id, activities, &mut memo, &mut checking) {
            validator.error(
                "AXC203",
                file,
                &format!("$.activities.{id}"),
                "Activity has a path that does not terminate in an end activity.",
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn root() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../examples/prime-and-maximal")
    }

    #[test]
    fn strict_json_rejects_duplicate_keys_at_nested_depth() {
        let error =
            parse_json_strict(br#"{"outer":{"answer":1,"answer":2}}"#, "input.json").unwrap_err();
        assert_eq!(error.code, "AXC104");
        assert_eq!(error.message, "JSON object contains a duplicate key.");
    }

    #[test]
    fn strict_json_rejects_trailing_data() {
        let error = parse_json_strict(br#"{"ok":true} false"#, "input.json").unwrap_err();
        assert_eq!(error.code, "AXC100");
    }

    #[test]
    fn bounded_json_enforces_package_nesting_limit() {
        let nested = format!("{}0{}", "[".repeat(33), "]".repeat(33));
        let error = parse_bounded_json(nested.as_bytes(), "deep.json").unwrap_err();
        assert_eq!(error.code, "AXC100");
        assert_eq!(error.message, "deep.json exceeds JSON nesting limit.");
    }

    #[test]
    fn kotlin_reference_fixture_validates() {
        let result = validate_directory(&root());
        assert!(result.valid, "{:#?}", result.diagnostics);
        assert!(result.diagnostics.is_empty());
    }

    #[test]
    fn stable_id_and_version_boundaries_match_content_contract() {
        assert!(valid_id("axiora.ca.prime-vs-maximal"));
        assert!(!valid_id("Axiora.bad"));
        assert!(!valid_id("axiora..bad"));
        assert!(valid_revision("0.1.0"));
        assert!(!valid_revision("0.1"));
        assert!(!valid_revision("0.1.0-rc1"));
    }
}
