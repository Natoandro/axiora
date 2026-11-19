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

mod pack;
pub use pack::pack_directory;

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
#[path = "archive_tests.rs"]
mod tests;
