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
    BuildResult, LoadedPackage, inspect_archive, load_archive, load_archive_in, pack_directory,
    write_loaded_package,
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

mod validation;
pub use validation::validate_directory;
