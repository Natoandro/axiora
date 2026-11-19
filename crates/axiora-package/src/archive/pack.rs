use super::*;

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
