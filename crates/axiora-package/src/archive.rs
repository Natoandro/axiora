use crate::{
    ARCHIVE_BYTES, Diagnostic, MANIFEST_BYTES, PAYLOAD_BYTES, PackageDescriptor, PackageError,
    TOTAL_BYTES, parse_bounded_json, parse_json_strict, sha256, validate_directory,
};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipArchive, ZipWriter};

static TEMP_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone)]
pub struct LoadedPackage {
    pub descriptor: PackageDescriptor,
    pub manifest: Value,
    pub manifest_bytes: Vec<u8>,
    /// Owned payload bytes keyed by their package-relative path.
    pub payloads: BTreeMap<String, Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct BuildResult {
    pub descriptor: PackageDescriptor,
    pub report_path: PathBuf,
}

struct ZipEntry {
    name: String,
    method: u16,
    compressed_size: u32,
    uncompressed_size: u32,
    offset: u32,
    made_by: u16,
    external_attributes: u32,
    central_position: usize,
}

pub fn inspect_archive(path: &Path) -> Result<PackageDescriptor, PackageError> {
    Ok(load_archive(path)?.descriptor)
}

pub fn load_archive(path: &Path) -> Result<LoadedPackage, PackageError> {
    load_archive_in(path, &std::env::temp_dir())
}

/// Validate and load an archive using a caller-provided scratch directory.
pub fn load_archive_in(path: &Path, scratch: &Path) -> Result<LoadedPackage, PackageError> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err(PackageError::Invalid(
            "Archive is not a regular file.".into(),
        ));
    }
    if metadata.len() > ARCHIVE_BYTES {
        return Err(PackageError::Invalid(format!(
            "Archive exceeds {ARCHIVE_BYTES} bytes."
        )));
    }
    let bytes = fs::read(path)?;
    let entries = parse_directory(&bytes, true)?;
    if entries.is_empty() || entries.len() > crate::FILES {
        return Err(PackageError::Invalid(
            "Archive entry count is outside supported limits.".into(),
        ));
    }
    let names = entries
        .iter()
        .map(|entry| entry.name.as_str())
        .collect::<Vec<_>>();
    if names.len() != entries.len() || names.iter().collect::<HashSet<_>>().len() != names.len() {
        return Err(PackageError::Invalid(
            "Archive contains duplicate entry names.".into(),
        ));
    }
    let folded = names
        .iter()
        .map(|name| name.to_lowercase())
        .collect::<Vec<_>>();
    if folded.iter().collect::<HashSet<_>>().len() != folded.len() {
        return Err(PackageError::Invalid(
            "Archive contains case-insensitive path collisions.".into(),
        ));
    }
    for entry in &entries {
        validate_archive_path(&entry.name)?;
    }
    for name in &names {
        let mut parent_path = Path::new(name).parent();
        while let Some(path) = parent_path {
            let parent_name = path.to_string_lossy().replace('\\', "/");
            if !parent_name.is_empty() && folded.contains(&parent_name.to_lowercase()) {
                return Err(PackageError::Invalid(format!(
                    "Archive file and directory paths collide: {parent_name} and {name}"
                )));
            }
            parent_path = path.parent();
        }
    }
    if !names.contains(&"manifest.json") {
        return Err(PackageError::Invalid(
            "Archive has no root manifest.json.".into(),
        ));
    }

    let mut total = 0_u64;
    let mut payloads = BTreeMap::new();
    let mut zip = ZipArchive::new(Cursor::new(bytes.as_slice()))
        .map_err(|error| PackageError::Invalid(error.to_string()))?;
    if zip.len() != entries.len() {
        return Err(PackageError::Invalid(
            "ZIP central directory does not match entries.".into(),
        ));
    }
    for (index, meta) in entries.iter().enumerate() {
        if meta.method != 0 || meta.compressed_size != meta.uncompressed_size {
            return Err(PackageError::Invalid(format!(
                "Only stored regular-file entries are supported: {}",
                meta.name
            )));
        }
        let limit = if meta.name == "manifest.json" {
            MANIFEST_BYTES
        } else {
            PAYLOAD_BYTES
        };
        if u64::from(meta.uncompressed_size) > limit {
            return Err(PackageError::Invalid(format!(
                "Entry exceeds size limit: {}",
                meta.name
            )));
        }
        total += u64::from(meta.uncompressed_size);
        if total > TOTAL_BYTES {
            return Err(PackageError::Invalid(
                "Expanded archive exceeds size limit.".into(),
            ));
        }
        let mut entry = zip
            .by_index(index)
            .map_err(|error| PackageError::Invalid(error.to_string()))?;
        if entry.name() != meta.name
            || entry.is_dir()
            || entry.is_symlink()
            || entry.encrypted()
            || entry.compression() != CompressionMethod::Stored
            || entry.size() != u64::from(meta.uncompressed_size)
            || entry.compressed_size() != u64::from(meta.compressed_size)
        {
            return Err(PackageError::Invalid(format!(
                "ZIP metadata does not match entry: {}",
                meta.name
            )));
        }
        if meta.made_by >> 8 == 3 {
            let kind = (meta.external_attributes >> 16) & 0xf000;
            if kind != 0x8000 {
                return Err(PackageError::Invalid(format!(
                    "Archive entry is not a regular file: {}",
                    meta.name
                )));
            }
        }
        let mut data = Vec::with_capacity(meta.uncompressed_size as usize);
        let mut limited = (&mut entry).take(limit + 1);
        limited.read_to_end(&mut data)?;
        if data.len() as u64 != u64::from(meta.uncompressed_size) || data.len() as u64 > limit {
            return Err(PackageError::Invalid(format!(
                "Entry size mismatch: {}",
                meta.name
            )));
        }
        if meta.name != "manifest.json" {
            payloads.insert(meta.name.clone(), data);
        } else {
            payloads.insert(meta.name.clone(), data);
        }
    }
    drop(zip);
    let manifest_bytes = payloads
        .remove("manifest.json")
        .ok_or_else(|| PackageError::Invalid("Archive has no root manifest.json.".into()))?;
    let manifest = parse_json_strict(&manifest_bytes, "manifest.json")
        .map_err(|diagnostic| PackageError::Invalid(diagnostic.message))?;
    let inventory = manifest
        .get("files")
        .and_then(Value::as_array)
        .ok_or_else(|| PackageError::Invalid("Manifest has no file inventory.".into()))?;
    let mut declared = HashSet::new();
    for record in inventory {
        let Some(name) = record.get("path").and_then(Value::as_str) else {
            return Err(PackageError::Invalid(
                "Manifest file inventory has an invalid path.".into(),
            ));
        };
        if !declared.insert(name.to_owned()) {
            return Err(PackageError::Invalid(format!(
                "Manifest declares duplicate payload '{name}'."
            )));
        }
    }
    let actual = payloads.keys().cloned().collect::<HashSet<_>>();
    if declared != actual {
        return Err(PackageError::Invalid(
            "Archive entries do not match the manifest payload inventory.".into(),
        ));
    }
    for record in inventory {
        let path = record
            .get("path")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let data = payloads
            .get(path)
            .expect("inventory equality checked above");
        let declared_bytes = record.get("bytes").and_then(Value::as_u64);
        let declared_hash = record.get("sha256").and_then(Value::as_str);
        if declared_bytes != Some(data.len() as u64) {
            return Err(PackageError::Invalid(format!(
                "Byte count does not match the manifest: {path}"
            )));
        }
        if declared_hash != Some(sha256(data).as_str()) {
            return Err(PackageError::Invalid(format!(
                "SHA-256 does not match the manifest: {path}"
            )));
        }
        parse_bounded_json(data, path)
            .map_err(|diagnostic| PackageError::Invalid(diagnostic.message))?;
    }

    let staging = TempDir::new_in("axiora-load", scratch)?;
    fs::write(staging.path().join("manifest.json"), &manifest_bytes)?;
    for (name, data) in &payloads {
        let target = staging.path().join(name);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(target, data)?;
    }
    let validation = validate_directory(staging.path());
    if !validation.valid {
        return Err(PackageError::Invalid(
            validation
                .diagnostics
                .iter()
                .map(format_diagnostic)
                .collect::<Vec<_>>()
                .join("\n"),
        ));
    }
    let package_id = manifest
        .get("package_id")
        .and_then(Value::as_str)
        .ok_or_else(|| PackageError::Invalid("Manifest package_id is invalid.".into()))?
        .to_owned();
    let version = manifest
        .get("version")
        .and_then(Value::as_str)
        .ok_or_else(|| PackageError::Invalid("Manifest version is invalid.".into()))?
        .to_owned();
    let descriptor = PackageDescriptor {
        package_id,
        version,
        manifest_sha256: sha256(&manifest_bytes),
        archive_sha256: sha256(&bytes),
        payload_count: payloads.len(),
        uncompressed_bytes: total,
    };
    Ok(LoadedPackage {
        descriptor,
        manifest,
        manifest_bytes,
        payloads,
    })
}

/// Write already validated package files into an empty caller-owned staging directory.
/// The caller remains responsible for removing the directory after consuming the data.
pub fn write_loaded_package(package: &LoadedPackage, staging: &Path) -> Result<(), PackageError> {
    package.write_to(staging)
}

impl LoadedPackage {
    /// Write the package into an empty caller-owned staging directory.
    pub fn write_to(&self, staging: &Path) -> Result<(), PackageError> {
        let metadata = fs::symlink_metadata(staging)?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(PackageError::Invalid(
                "Package staging path is not a private directory.".into(),
            ));
        }
        if fs::read_dir(staging)?.next().is_some() {
            return Err(PackageError::Invalid(
                "Package staging directory is not empty.".into(),
            ));
        }
        let mut files = BTreeMap::new();
        files.insert("manifest.json".to_owned(), self.manifest_bytes.as_slice());
        for (path, bytes) in &self.payloads {
            files.insert(path.clone(), bytes.as_slice());
        }
        for (relative, bytes) in files {
            if !crate::validation::safe_relative_path(&relative) {
                return Err(PackageError::Invalid(format!(
                    "Unsafe package path: {relative}"
                )));
            }
            let target = staging.join(&relative);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            let mut output = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(target)?;
            output.write_all(bytes)?;
        }
        Ok(())
    }
}

mod pack;
pub use pack::pack_directory;

mod zip_directory;
use zip_directory::{mark_utf8_flags, parse_directory, validate_archive_path};

fn format_diagnostic(diagnostic: &Diagnostic) -> String {
    format!(
        "{} {}:{}: {}",
        diagnostic.code, diagnostic.file, diagnostic.path, diagnostic.message
    )
}

fn canonical_json(value: Value) -> Vec<u8> {
    fn sort(value: Value) -> Value {
        match value {
            Value::Object(object) => {
                let sorted = object
                    .into_iter()
                    .map(|(key, value)| (key, sort(value)))
                    .collect::<BTreeMap<_, _>>();
                Value::Object(sorted.into_iter().collect())
            }
            Value::Array(values) => Value::Array(values.into_iter().map(sort).collect()),
            other => other,
        }
    }
    serde_json::to_vec(&sort(value)).expect("JSON values always serialize")
}

fn absolute_lexical(path: &Path) -> Result<PathBuf, PackageError> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    let mut result = PathBuf::new();
    for component in absolute.components() {
        match component {
            std::path::Component::CurDir => (),
            std::path::Component::ParentDir => {
                result.pop();
            }
            other => result.push(other.as_os_str()),
        }
    }
    Ok(result)
}

mod temp;
use temp::{TempDir, TempFile, write_atomic};

#[cfg(test)]
#[path = "archive_tests.rs"]
mod tests;
