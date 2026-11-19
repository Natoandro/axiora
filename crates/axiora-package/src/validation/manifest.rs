use super::*;

pub(super) fn parse_manifest_units(
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

pub(super) fn parse_files(
    validator: &mut Validator,
    value: Option<&Value>,
) -> Vec<(String, u64, String)> {
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

pub(super) fn parse_review(validator: &mut Validator, value: Option<&Value>) {
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

pub(super) fn collect_payload_files(root: &Path) -> BTreeSet<String> {
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
