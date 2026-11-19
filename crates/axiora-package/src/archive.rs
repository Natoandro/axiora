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
    files.insert(
        "manifest.json".to_owned(),
        package.manifest_bytes.as_slice(),
    );
    for (path, bytes) in &package.payloads {
        files.insert(path.clone(), bytes.as_slice());
    }
    for (relative, bytes) in files {
        if !super::safe_relative_path(&relative) {
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

pub fn pack_directory(
    source: &Path,
    output: &Path,
    overwrite: bool,
) -> Result<BuildResult, PackageError> {
    let source = fs::canonicalize(source)?;
    if !source.is_dir() {
        return Err(PackageError::Invalid(format!(
            "Input directory does not exist: {}",
            source.display()
        )));
    }
    let output = absolute_lexical(output)?;
    if output.starts_with(&source) {
        return Err(PackageError::Invalid(format!(
            "Output must be outside the authoring directory: {}",
            output.display()
        )));
    }
    if output.exists() && !overwrite {
        return Err(PackageError::Invalid(format!(
            "Output already exists: {} (use --overwrite)",
            output.display()
        )));
    }
    let validation = validate_directory(&source);
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
    let manifest_source = fs::read(source.join("manifest.json"))?;
    let mut manifest = parse_json_strict(&manifest_source, "manifest.json")
        .map_err(|diagnostic| PackageError::Invalid(diagnostic.message))?;
    let files = manifest
        .get("files")
        .and_then(Value::as_array)
        .ok_or_else(|| PackageError::Invalid("Manifest has no file inventory.".into()))?;
    let mut source_hashes = BTreeMap::new();
    source_hashes.insert("manifest.json".to_owned(), sha256(&manifest_source));
    let mut paths = files
        .iter()
        .filter_map(|file| file.get("path").and_then(Value::as_str).map(str::to_owned))
        .collect::<Vec<_>>();
    paths.sort();
    paths.dedup();
    let mut payloads = BTreeMap::new();
    let mut raw_total = 0_u64;
    for path in paths {
        let raw = fs::read(source.join(&path))?;
        source_hashes.insert(path.clone(), sha256(&raw));
        if raw.len() as u64 > PAYLOAD_BYTES {
            return Err(PackageError::Invalid(format!(
                "Payload '{path}' exceeds {PAYLOAD_BYTES} bytes."
            )));
        }
        raw_total += raw.len() as u64;
        if raw_total > TOTAL_BYTES {
            return Err(PackageError::Invalid(format!(
                "Package exceeds {TOTAL_BYTES} uncompressed bytes."
            )));
        }
        let value = parse_bounded_json(&raw, &path)
            .map_err(|diagnostic| PackageError::Invalid(diagnostic.message))?;
        payloads.insert(path, canonical_json(value));
    }
    if payloads.len() + 1 > crate::FILES {
        return Err(PackageError::Invalid(format!(
            "Package exceeds {} archive files.",
            crate::FILES
        )));
    }
    let mut file_records = Vec::new();
    for (path, data) in &payloads {
        file_records.push(json!({"path":path,"bytes":data.len(),"sha256":sha256(data)}));
    }
    manifest
        .as_object_mut()
        .ok_or_else(|| PackageError::Invalid("Manifest is not an object.".into()))?
        .insert("files".into(), Value::Array(file_records));
    let manifest_bytes = canonical_json(manifest.clone());
    if manifest_bytes.len() as u64 > MANIFEST_BYTES {
        return Err(PackageError::Invalid(format!(
            "Manifest exceeds {MANIFEST_BYTES} bytes."
        )));
    }
    let mut entries = payloads;
    entries.insert("manifest.json".into(), manifest_bytes.clone());
    let archive_uncompressed = entries.values().map(|data| data.len() as u64).sum::<u64>();
    if archive_uncompressed > TOTAL_BYTES {
        return Err(PackageError::Invalid(format!(
            "Package exceeds {TOTAL_BYTES} uncompressed bytes."
        )));
    }
    let parent = output.parent().unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent)?;
    let temp = TempFile::new(parent, output.file_name().unwrap_or_default())?;
    {
        let file = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(temp.path())?;
        let mut writer = ZipWriter::new(file);
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Stored)
            .last_modified_time(DateTime::default())
            .unix_permissions(0o100644);
        for (name, data) in &entries {
            writer
                .start_file(name, options)
                .map_err(|error| PackageError::Invalid(error.to_string()))?;
            writer.write_all(data)?;
        }
        writer
            .finish()
            .map_err(|error| PackageError::Invalid(error.to_string()))?;
    }
    if fs::metadata(temp.path())?.len() > ARCHIVE_BYTES {
        return Err(PackageError::Invalid(format!(
            "Archive exceeds {ARCHIVE_BYTES} bytes."
        )));
    }
    let mut archive_bytes = fs::read(temp.path())?;
    mark_utf8_flags(&mut archive_bytes)?;
    fs::write(temp.path(), &archive_bytes)?;
    if overwrite {
        #[cfg(windows)]
        if output.exists() {
            fs::remove_file(&output)?;
        }
        fs::rename(temp.path(), &output)?;
    } else {
        // A hard link is an atomic create-if-absent publication on the same filesystem.
        // Do not fall back to rename: on Unix it may replace a concurrently-created target.
        fs::hard_link(temp.path(), &output)?;
    }
    let archive_bytes = fs::read(&output)?;
    let package_id = manifest["package_id"]
        .as_str()
        .unwrap_or_default()
        .to_owned();
    let version = manifest["version"].as_str().unwrap_or_default().to_owned();
    let descriptor = PackageDescriptor {
        package_id: package_id.clone(),
        version: version.clone(),
        manifest_sha256: sha256(&manifest_bytes),
        archive_sha256: sha256(&archive_bytes),
        payload_count: entries.len() - 1,
        uncompressed_bytes: archive_uncompressed,
    };
    let report_path = PathBuf::from(format!("{}.report.json", output.display()));
    let mut sorted_diagnostics = validation.diagnostics;
    sorted_diagnostics.sort_by(|a, b| {
        (&a.code, &a.file, &a.path, &a.message).cmp(&(&b.code, &b.file, &b.path, &b.message))
    });
    let diagnostics = sorted_diagnostics
        .iter()
        .map(|diagnostic| {
            let mut value = json!({
                "code": diagnostic.code,
                "severity": diagnostic.severity,
                "file": diagnostic.file,
                "path": diagnostic.path,
                "message": diagnostic.message,
            });
            if let Some(suggestion) = &diagnostic.suggestion {
                value["suggestion"] = Value::String(suggestion.clone());
            }
            value
        })
        .collect::<Vec<_>>();
    let source_hash_values = source_hashes
        .into_iter()
        .map(|(path, hash)| (path, Value::String(hash)))
        .collect::<serde_json::Map<_, _>>();
    let report = json!({
        "tool":"axiora-compiler-0.1.0",
        "policy":"package-0.1",
        "package_id":package_id,
        "version":version,
        "manifest_sha256":descriptor.manifest_sha256,
        "archive_sha256":descriptor.archive_sha256,
        "payload_count":descriptor.payload_count,
        "uncompressed_bytes":raw_total + manifest_bytes.len() as u64,
        "source_sha256":source_hash_values,
        "diagnostics":diagnostics,
    });
    write_atomic(&report_path, &canonical_json(report))?;
    Ok(BuildResult {
        descriptor,
        report_path,
    })
}

fn parse_directory(bytes: &[u8], require_utf8_flag: bool) -> Result<Vec<ZipEntry>, PackageError> {
    let lower = bytes.len().saturating_sub(65_557);
    let end_record = (lower..=bytes.len().saturating_sub(22))
        .rev()
        .find(|position| read_u32(bytes, *position).ok() == Some(0x06054b50))
        .ok_or_else(|| PackageError::Invalid("ZIP end record is missing.".into()))?;
    let disk = read_u16(bytes, end_record + 4)?;
    let central_disk = read_u16(bytes, end_record + 6)?;
    let disk_count = read_u16(bytes, end_record + 8)?;
    let count = read_u16(bytes, end_record + 10)?;
    let size = read_u32(bytes, end_record + 12)?;
    let offset = read_u32(bytes, end_record + 16)?;
    let comment_length = read_u16(bytes, end_record + 20)? as usize;
    if disk != 0
        || central_disk != 0
        || disk_count != count
        || count == u16::MAX
        || size == u32::MAX
        || offset == u32::MAX
    {
        return Err(PackageError::Invalid(
            "Multi-disk and ZIP64 archives are unsupported.".into(),
        ));
    }
    if end_record + 22 + comment_length != bytes.len()
        || u64::from(offset) + u64::from(size) > end_record as u64
    {
        return Err(PackageError::Invalid(
            "Invalid ZIP central directory bounds.".into(),
        ));
    }
    let mut position = offset as usize;
    let mut result = Vec::with_capacity(count as usize);
    for _ in 0..count {
        if read_u32(bytes, position)? != 0x02014b50 {
            return Err(PackageError::Invalid(
                "Invalid ZIP central directory entry.".into(),
            ));
        }
        let made_by = read_u16(bytes, position + 4)?;
        let flags = read_u16(bytes, position + 8)?;
        let method = read_u16(bytes, position + 10)?;
        let crc = read_u32(bytes, position + 16)?;
        let compressed_size = read_u32(bytes, position + 20)?;
        let uncompressed_size = read_u32(bytes, position + 24)?;
        let name_len = read_u16(bytes, position + 28)? as usize;
        let extra_len = read_u16(bytes, position + 30)? as usize;
        let comment_len = read_u16(bytes, position + 32)? as usize;
        let disk_start = read_u16(bytes, position + 34)?;
        let external_attributes = read_u32(bytes, position + 38)?;
        let local_offset = read_u32(bytes, position + 42)?;
        let end = position
            .checked_add(46 + name_len + extra_len + comment_len)
            .ok_or_else(|| {
                PackageError::Invalid("Truncated ZIP central directory entry.".into())
            })?;
        if end > bytes.len()
            || disk_start != 0
            || flags & 1 != 0
            || (require_utf8_flag && flags & (1 << 11) == 0)
            || compressed_size == u32::MAX
            || uncompressed_size == u32::MAX
        {
            return Err(PackageError::Invalid(
                "Encrypted, multi-disk, non-UTF-8, or ZIP64 entries are unsupported.".into(),
            ));
        }
        let name_bytes = &bytes[position + 46..position + 46 + name_len];
        let name = std::str::from_utf8(name_bytes)
            .map_err(|_| PackageError::Invalid("ZIP entry names must be valid UTF-8.".into()))?
            .to_owned();
        if flags & (1 << 11) == 0 && !name.is_ascii() {
            return Err(PackageError::Invalid(
                "ZIP entry names must be explicitly UTF-8.".into(),
            ));
        }
        let local = local_offset as usize;
        if local as u64 >= u64::from(offset) || read_u32(bytes, local)? != 0x04034b50 {
            return Err(PackageError::Invalid(format!(
                "Invalid ZIP local file header: {name}"
            )));
        }
        let local_flags = read_u16(bytes, local + 6)?;
        let local_method = read_u16(bytes, local + 8)?;
        let local_crc = read_u32(bytes, local + 14)?;
        let local_compressed = read_u32(bytes, local + 18)?;
        let local_uncompressed = read_u32(bytes, local + 22)?;
        let local_name_len = read_u16(bytes, local + 26)? as usize;
        let local_extra_len = read_u16(bytes, local + 28)? as usize;
        let data_start = local
            .checked_add(30 + local_name_len + local_extra_len)
            .ok_or_else(|| PackageError::Invalid("Invalid ZIP local file header.".into()))?;
        let data_end = data_start
            .checked_add(compressed_size as usize)
            .ok_or_else(|| PackageError::Invalid("Invalid ZIP local file header.".into()))?;
        if local_flags != flags
            || local_method != method
            || data_end > offset as usize
            || local + 30 + local_name_len > bytes.len()
            || std::str::from_utf8(&bytes[local + 30..local + 30 + local_name_len]).ok()
                != Some(name.as_str())
        {
            return Err(PackageError::Invalid(format!(
                "ZIP local header does not match central directory: {name}"
            )));
        }
        if flags & 8 == 0
            && (local_crc != crc
                || local_compressed != compressed_size
                || local_uncompressed != uncompressed_size)
        {
            return Err(PackageError::Invalid(format!(
                "ZIP local sizes or CRC do not match central directory: {name}"
            )));
        }
        let central_position = position;
        position = end;
        result.push(ZipEntry {
            name,
            method,
            compressed_size,
            uncompressed_size,
            offset: local_offset,
            made_by,
            external_attributes,
            central_position,
        });
    }
    if position as u64 != u64::from(offset) + u64::from(size) {
        return Err(PackageError::Invalid(
            "Unexpected data in ZIP central directory.".into(),
        ));
    }
    Ok(result)
}

fn mark_utf8_flags(bytes: &mut [u8]) -> Result<(), PackageError> {
    let entries = parse_directory(bytes, false)?;
    for entry in entries {
        let local = entry.offset as usize;
        let local_flags = read_u16(bytes, local + 6)? | (1 << 11);
        let central_flags = read_u16(bytes, entry.central_position + 8)? | (1 << 11);
        bytes[local + 6..local + 8].copy_from_slice(&local_flags.to_le_bytes());
        bytes[entry.central_position + 8..entry.central_position + 10]
            .copy_from_slice(&central_flags.to_le_bytes());
    }
    parse_directory(bytes, true)?;
    Ok(())
}

fn validate_archive_path(name: &str) -> Result<(), PackageError> {
    if name.is_empty()
        || name.starts_with('/')
        || name.contains('\\')
        || name.contains('\0')
        || name
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        return Err(PackageError::Invalid(format!(
            "Unsafe archive path: {name}"
        )));
    }
    Ok(())
}

fn read_u16(bytes: &[u8], position: usize) -> Result<u16, PackageError> {
    let bytes = bytes
        .get(position..position + 2)
        .ok_or_else(|| PackageError::Invalid("Truncated ZIP metadata.".into()))?;
    Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
}

fn read_u32(bytes: &[u8], position: usize) -> Result<u32, PackageError> {
    let bytes = bytes
        .get(position..position + 4)
        .ok_or_else(|| PackageError::Invalid("Truncated ZIP metadata.".into()))?;
    Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

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

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), PackageError> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let temp = TempFile::new(parent, path.file_name().unwrap_or_default())?;
    fs::write(temp.path(), bytes)?;
    #[cfg(windows)]
    if path.exists() {
        fs::remove_file(path)?;
    }
    fs::rename(temp.path(), path)?;
    Ok(())
}

struct TempFile {
    path: PathBuf,
}
impl TempFile {
    fn new(parent: &Path, name: &std::ffi::OsStr) -> Result<Self, PackageError> {
        for _ in 0..100 {
            let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path = parent.join(format!(".{}.axiora-{id}.tmp", name.to_string_lossy()));
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(_) => return Ok(Self { path }),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
        Err(PackageError::Invalid(
            "Could not allocate a temporary output file.".into(),
        ))
    }
    fn path(&self) -> &Path {
        &self.path
    }
}
impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

struct TempDir {
    path: PathBuf,
}
impl TempDir {
    #[cfg(test)]
    fn new(prefix: &str) -> Result<Self, PackageError> {
        Self::new_in(prefix, &std::env::temp_dir())
    }

    fn new_in(prefix: &str, base: &Path) -> Result<Self, PackageError> {
        for _ in 0..100 {
            let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path = base.join(format!("{prefix}-{}-{id}", std::process::id()));
            match fs::create_dir(&path) {
                Ok(()) => {
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt;
                        fs::set_permissions(&path, fs::Permissions::from_mode(0o700))?;
                    }
                    return Ok(Self { path });
                }
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
        Err(PackageError::Invalid(
            "Could not allocate a private staging directory.".into(),
        ))
    }
    fn path(&self) -> &Path {
        &self.path
    }
}
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn reference_archive() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/kotlin-reference/prime-and-maximal.axp")
    }

    fn mutate_reference(
        name: &str,
        mutation: impl FnOnce(&mut Vec<u8>, &ZipEntry),
    ) -> Result<(), PackageError> {
        let mut bytes = fs::read(reference_archive())?;
        let entry = parse_directory(&bytes, true)?
            .into_iter()
            .find(|entry| entry.name == name)
            .ok_or_else(|| PackageError::Invalid(format!("Missing test entry {name}")))?;
        mutation(&mut bytes, &entry);
        let dir = TempDir::new("axiora-mutated")?;
        let path = dir.path().join("mutated.axp");
        fs::write(&path, bytes)?;
        inspect_archive(&path).map(|_| ())
    }

    fn reference_entries() -> BTreeMap<String, Vec<u8>> {
        let bytes = fs::read(reference_archive()).unwrap();
        let mut archive = ZipArchive::new(Cursor::new(bytes)).unwrap();
        let mut entries = BTreeMap::new();
        for index in 0..archive.len() {
            let mut entry = archive.by_index(index).unwrap();
            let name = entry.name().to_owned();
            let mut content = Vec::new();
            entry.read_to_end(&mut content).unwrap();
            entries.insert(name, content);
        }
        entries
    }

    fn write_entries(path: &Path, entries: &BTreeMap<String, Vec<u8>>) {
        let file = fs::File::create(path).unwrap();
        let mut writer = ZipWriter::new(file);
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Stored)
            .last_modified_time(DateTime::default())
            .unix_permissions(0o100644);
        for (name, content) in entries {
            writer.start_file(name, options).unwrap();
            writer.write_all(content).unwrap();
        }
        writer.finish().unwrap();
        let mut bytes = fs::read(path).unwrap();
        mark_utf8_flags(&mut bytes).unwrap();
        fs::write(path, bytes).unwrap();
    }

    fn write_manifest_variant(
        entries: &mut BTreeMap<String, Vec<u8>>,
        edit: impl FnOnce(String) -> String,
    ) {
        let manifest = String::from_utf8(entries["manifest.json"].clone()).unwrap();
        entries.insert("manifest.json".into(), edit(manifest).into_bytes());
    }

    #[test]
    fn rejects_traversal_names_before_staging() {
        let error = mutate_reference("concepts/core.json", |bytes, entry| {
            let replacement = b"../outside.jsonxxx";
            assert_eq!(replacement.len(), entry.name.len());
            let local_name = entry.offset as usize + 30;
            let central_name = entry.central_position + 46;
            bytes[local_name..local_name + replacement.len()].copy_from_slice(replacement);
            bytes[central_name..central_name + replacement.len()].copy_from_slice(replacement);
        })
        .unwrap_err();
        assert!(error.to_string().contains("Unsafe archive path"));
    }

    #[test]
    fn rejects_encrypted_entries_and_nonregular_unix_metadata() {
        let error = mutate_reference("manifest.json", |bytes, entry| {
            let local_flags = read_u16(bytes, entry.offset as usize + 6).unwrap() | 1;
            let central_flags = read_u16(bytes, entry.central_position + 8).unwrap() | 1;
            bytes[entry.offset as usize + 6..entry.offset as usize + 8]
                .copy_from_slice(&local_flags.to_le_bytes());
            bytes[entry.central_position + 8..entry.central_position + 10]
                .copy_from_slice(&central_flags.to_le_bytes());
        })
        .unwrap_err();
        assert!(error.to_string().contains("Encrypted"));

        let error = mutate_reference("manifest.json", |bytes, entry| {
            bytes[entry.central_position + 5] = 3;
            bytes[entry.central_position + 40..entry.central_position + 42]
                .copy_from_slice(&0xa1ff_u16.to_le_bytes());
        })
        .unwrap_err();
        assert!(
            error.to_string().contains("regular file")
                || error.to_string().contains("does not match entry"),
            "{error}"
        );
    }

    #[test]
    fn rejects_malformed_utf8_names_and_central_local_mismatches() {
        let error = mutate_reference("manifest.json", |bytes, entry| {
            let local_name = entry.offset as usize + 30;
            bytes[local_name] = 0xff;
            bytes[entry.central_position + 46] = 0xff;
        })
        .unwrap_err();
        assert!(error.to_string().contains("valid UTF-8"));

        let error = mutate_reference("manifest.json", |bytes, entry| {
            let position = entry.central_position + 24;
            let size = read_u32(bytes, position).unwrap() + 1;
            bytes[position..position + 4].copy_from_slice(&size.to_le_bytes());
        })
        .unwrap_err();
        assert!(error.to_string().contains("local sizes or CRC"));
    }

    #[test]
    fn rejects_corrupt_payloads_and_compressed_entries() {
        let error = mutate_reference("concepts/core.json", |bytes, entry| {
            let local = entry.offset as usize;
            let data = local
                + 30
                + read_u16(bytes, local + 26).unwrap() as usize
                + read_u16(bytes, local + 28).unwrap() as usize;
            bytes[data] ^= 1;
        })
        .unwrap_err();
        assert!(!error.to_string().is_empty());

        let error = mutate_reference("manifest.json", |bytes, entry| {
            let local_method = entry.offset as usize + 8;
            bytes[local_method..local_method + 2].copy_from_slice(&8_u16.to_le_bytes());
            bytes[entry.central_position + 10..entry.central_position + 12]
                .copy_from_slice(&8_u16.to_le_bytes());
        })
        .unwrap_err();
        assert!(error.to_string().contains("Only stored"));
    }

    #[test]
    fn rejects_archive_over_limit_before_reading_contents() {
        let dir = TempDir::new("axiora-oversized").unwrap();
        let archive = dir.path().join("oversized.axp");
        let file = fs::File::create(&archive).unwrap();
        file.set_len(ARCHIVE_BYTES + 1).unwrap();
        let error = inspect_archive(&archive).unwrap_err();
        assert!(error.to_string().contains("Archive exceeds"));
    }

    #[test]
    fn rejects_duplicate_names_unknown_capabilities_bad_hashes_and_undeclared_files() {
        let dir = TempDir::new("axiora-invalid-inventory").unwrap();

        let mut bytes = fs::read(reference_archive()).unwrap();
        let eocd = (0..=bytes.len() - 22)
            .rev()
            .find(|position| read_u32(&bytes, *position).ok() == Some(0x06054b50))
            .unwrap();
        let first = parse_directory(&bytes, true).unwrap().remove(0);
        let record_len = 46
            + read_u16(&bytes, first.central_position + 28).unwrap() as usize
            + read_u16(&bytes, first.central_position + 30).unwrap() as usize
            + read_u16(&bytes, first.central_position + 32).unwrap() as usize;
        let mut duplicate = Vec::with_capacity(bytes.len() + record_len);
        duplicate.extend_from_slice(&bytes[..eocd]);
        duplicate
            .extend_from_slice(&bytes[first.central_position..first.central_position + record_len]);
        duplicate.extend_from_slice(&bytes[eocd..]);
        bytes = duplicate;
        let new_eocd = eocd + record_len;
        for count_offset in [new_eocd + 8, new_eocd + 10] {
            let count = read_u16(&bytes, count_offset).unwrap() + 1;
            bytes[count_offset..count_offset + 2].copy_from_slice(&count.to_le_bytes());
        }
        let directory_size = read_u32(&bytes, new_eocd + 12).unwrap() + record_len as u32;
        bytes[new_eocd + 12..new_eocd + 16].copy_from_slice(&directory_size.to_le_bytes());
        let duplicate_path = dir.path().join("duplicate.axp");
        fs::write(&duplicate_path, bytes).unwrap();
        assert!(
            inspect_archive(&duplicate_path)
                .unwrap_err()
                .to_string()
                .contains("duplicate entry")
        );

        let mut entries = reference_entries();
        write_manifest_variant(&mut entries, |manifest| {
            manifest.replace("activity.end.v1", "activity.bad.v1")
        });
        let unknown = dir.path().join("unknown-capability.axp");
        write_entries(&unknown, &entries);
        assert!(
            inspect_archive(&unknown)
                .unwrap_err()
                .to_string()
                .contains("Unsupported required capability")
        );

        let mut entries = reference_entries();
        write_manifest_variant(&mut entries, |manifest| {
            manifest.replacen(
                "\"sha256\":\"",
                &format!("\"sha256\":\"{}", "0".repeat(64)),
                1,
            )
        });
        let corrupt_hash = dir.path().join("corrupt-hash.axp");
        write_entries(&corrupt_hash, &entries);
        assert!(
            inspect_archive(&corrupt_hash)
                .unwrap_err()
                .to_string()
                .contains("SHA-256")
        );

        let mut entries = reference_entries();
        entries.insert("extra.json".into(), b"{}".to_vec());
        let undeclared = dir.path().join("undeclared.axp");
        write_entries(&undeclared, &entries);
        assert!(
            inspect_archive(&undeclared)
                .unwrap_err()
                .to_string()
                .contains("inventory")
        );

        let mut entries = reference_entries();
        let original = entries["units/prerequisite-recognition.json"].clone();
        entries.insert("Units/prerequisite-recognition.json".into(), original);
        let collision = dir.path().join("case-collision.axp");
        write_entries(&collision, &entries);
        assert!(
            inspect_archive(&collision)
                .unwrap_err()
                .to_string()
                .contains("case-insensitive")
        );
    }

    #[test]
    fn rejects_duplicate_json_keys_in_packaged_manifest() {
        let dir = TempDir::new("axiora-duplicate-json").unwrap();
        let mut entries = reference_entries();
        write_manifest_variant(&mut entries, |manifest| {
            manifest.replace(
                "\"package_id\":\"axiora.ca.first-slice\"",
                "\"package_id\":\"axiora.ca.first-slice\",\"package_id\":\"duplicate\"",
            )
        });
        let archive = dir.path().join("duplicate-key.axp");
        write_entries(&archive, &entries);
        assert!(
            inspect_archive(&archive)
                .unwrap_err()
                .to_string()
                .contains("duplicate key")
        );
    }
}
