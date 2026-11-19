use axiora_package::{
    FILES, MANIFEST_BYTES, PAYLOAD_BYTES, TOTAL_BYTES, parse_bounded_json, sha256,
    validate_directory,
};
use serde_json::Value;
use std::{
    collections::HashSet,
    fs,
    io::{Read, Write},
    path::{Component, Path},
};

fn read_regular(root: &Path, relative: &str, limit: u64) -> Result<Vec<u8>, String> {
    if relative.is_empty()
        || relative.contains(['\\', '\0'])
        || relative
            .split('/')
            .any(|v| v.is_empty() || v == "." || v == "..")
        || !Path::new(relative)
            .components()
            .all(|c| matches!(c, Component::Normal(_)))
    {
        return Err(format!("Unsafe path: {relative}"));
    }
    let mut path = root.to_path_buf();
    for part in Path::new(relative).components() {
        path.push(part);
        let m = fs::symlink_metadata(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        if m.file_type().is_symlink() {
            return Err(format!("Symlink not allowed: {}", path.display()));
        }
    }
    let m = fs::symlink_metadata(&path).map_err(|e| e.to_string())?;
    if !m.is_file() || m.len() > limit {
        return Err(format!(
            "Missing, non-regular or oversized file: {relative}"
        ));
    }
    let mut bytes = Vec::new();
    fs::File::open(&path)
        .map_err(|e| e.to_string())?
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() as u64 > limit {
        return Err(format!("File too large: {relative}"));
    }
    Ok(bytes)
}
fn execute(root: &Path, dry: bool) -> Result<(), String> {
    let meta = fs::symlink_metadata(root).map_err(|e| e.to_string())?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err("Directory must not be a symlink".into());
    }
    let original = read_regular(root, "manifest.json", MANIFEST_BYTES)?;
    let mut manifest = parse_bounded_json(&original, "manifest.json")
        .map_err(|d| format!("{}: {}", d.code, d.message))?;
    let entries = manifest
        .get("files")
        .and_then(Value::as_array)
        .ok_or("Missing files inventory")?;
    if entries.is_empty() || entries.len() > FILES {
        return Err("Invalid inventory size".into());
    }
    let mut seen = HashSet::new();
    let mut payloads = Vec::new();
    let mut total = 0u64;
    for entry in entries {
        let path = entry
            .get("path")
            .and_then(Value::as_str)
            .ok_or("Invalid inventory path")?;
        if path == "manifest.json" || path == "README.md" || !seen.insert(path.to_lowercase()) {
            return Err(format!("Invalid or duplicate inventory path: {path}"));
        }
        let bytes = read_regular(root, path, PAYLOAD_BYTES)?;
        total += bytes.len() as u64;
        if total > TOTAL_BYTES {
            return Err("Total payload exceeds limit".into());
        }
        payloads.push((path.to_string(), bytes));
    }
    for path in manifest
        .get("concept_files")
        .and_then(Value::as_array)
        .ok_or("Invalid concept inventory")?
        .iter()
        .filter_map(Value::as_str)
        .chain(
            manifest
                .get("units")
                .and_then(Value::as_array)
                .ok_or("Invalid unit inventory")?
                .iter()
                .filter_map(|u| u.get("path").and_then(Value::as_str)),
        )
    {
        if !seen.contains(&path.to_lowercase()) {
            return Err(format!("Referenced path not in inventory: {path}"));
        }
        read_regular(root, path, PAYLOAD_BYTES)?;
    }
    let check = validate_directory(root);
    let errors: Vec<_> = check
        .diagnostics
        .iter()
        .filter(|d| {
            d.severity == "error"
                && !(d.code == "AXC103"
                    && (d.message.starts_with("Byte count is ")
                        || d.message == "SHA-256 does not match the manifest."))
        })
        .collect();
    if !errors.is_empty() {
        return Err(format!(
            "Content invalid; fix {} at {}:{} before refresh: {}",
            errors[0].code, errors[0].file, errors[0].path, errors[0].message
        ));
    }
    let files = manifest["files"].as_array_mut().unwrap();
    let mut changed = 0;
    for (entry, (_, bytes)) in files.iter_mut().zip(&payloads) {
        let hash = sha256(bytes);
        if entry["bytes"] != bytes.len() as u64 || entry["sha256"] != hash {
            changed += 1
        }
        entry["bytes"] = Value::from(bytes.len() as u64);
        entry["sha256"] = Value::from(hash);
    }
    if !dry && changed > 0 {
        let mut output = serde_json::to_vec_pretty(&manifest).map_err(|e| e.to_string())?;
        output.push(b'\n');
        let tmp = root.join(".axiora-refresh.tmp");
        let mut f = fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&tmp)
            .map_err(|e| format!("{}: {e}", tmp.display()))?;
        let work = (|| -> Result<(), String> {
            f.write_all(&output).map_err(|e| e.to_string())?;
            drop(f);
            if read_regular(root, "manifest.json", MANIFEST_BYTES)? != original {
                return Err("Manifest changed during refresh".into());
            }
            for (path, bytes) in &payloads {
                if read_regular(root, path, PAYLOAD_BYTES)? != *bytes {
                    return Err(format!("Payload changed during refresh: {path}"));
                }
            }
            fs::rename(&tmp, root.join("manifest.json")).map_err(|e| e.to_string())?;
            Ok(())
        })();
        if work.is_err() {
            let _ = fs::remove_file(&tmp);
        }
        work?;
    }
    println!(
        "{} {changed} inventory entries",
        if dry { "Would refresh" } else { "Refreshed" }
    );
    Ok(())
}
pub fn run(root: &Path, dry: bool) -> i32 {
    match execute(root, dry) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("Refresh failed: {e}");
            2
        }
    }
}
