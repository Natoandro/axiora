use axiora_package::validate_directory;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn source() -> PathBuf {
    repo_root().join("examples/prime-and-maximal")
}

fn temp_dir() -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let path = std::env::temp_dir().join(format!("axiora-rust-port-{stamp}"));
    fs::create_dir(&path).unwrap();
    path
}

fn copy_tree(source: &std::path::Path, target: &std::path::Path) {
    fs::create_dir_all(target).unwrap();
    for entry in fs::read_dir(source).unwrap() {
        let entry = entry.unwrap();
        let from = entry.path();
        let to = target.join(entry.file_name());
        if from.is_dir() {
            copy_tree(&from, &to);
        } else {
            fs::copy(from, to).unwrap();
        }
    }
}

#[test]
fn kotlin_and_rust_cli_validate_baseline_with_same_exit_and_json_shape() {
    let output = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("validate")
        .arg(source())
        .arg("--format")
        .arg("json")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(0));
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        "{\"valid\":true,\"diagnostics\":[]}\n"
    );
    assert!(validate_directory(&source()).valid);
}

#[test]
fn invalid_cli_command_uses_usage_exit_code() {
    let output = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("unknown")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("Usage: axiora"));
}

#[test]
fn rust_cli_matches_invalid_json_diagnostic_shape_and_field_order() {
    let directory = temp_dir();
    let source_copy = directory.join("content");
    copy_tree(&source(), &source_copy);
    let manifest = source_copy.join("manifest.json");
    let contents = fs::read_to_string(&manifest).unwrap();
    fs::write(&manifest, contents.replacen("\"0.1.0\"", "\"0.2.0\"", 1)).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("validate")
        .arg(&source_copy)
        .arg("--format")
        .arg("json")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(1));
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        "{\"valid\":false,\"diagnostics\":[{\"code\":\"AXC101\",\"severity\":\"error\",\"file\":\"manifest.json\",\"path\":\"$.schema_version\",\"message\":\"Unsupported schema version '0.2.0'.\",\"suggestion\":\"This validator supports 0.1.0.\"}]}\n"
    );
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn cli_handles_unicode_paths_pack_inspect_and_no_overwrite() {
    let directory = temp_dir();
    let source_copy = directory.join("course files – café");
    copy_tree(&source(), &source_copy);
    let output_path = directory.join("package archive – 原始.axp");
    let validate = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("validate")
        .arg(&source_copy)
        .output()
        .unwrap();
    assert_eq!(validate.status.code(), Some(0));

    let pack = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("pack")
        .arg(&source_copy)
        .arg("--output")
        .arg(&output_path)
        .output()
        .unwrap();
    assert_eq!(
        pack.status.code(),
        Some(0),
        "{}",
        String::from_utf8_lossy(&pack.stderr)
    );
    let inspect = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("inspect")
        .arg(&output_path)
        .output()
        .unwrap();
    assert_eq!(inspect.status.code(), Some(0));
    assert!(String::from_utf8_lossy(&inspect.stdout).contains("axiora.ca.first-slice@0.1.0"));

    let original = fs::read(&output_path).unwrap();
    let refused = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("pack")
        .arg(&source_copy)
        .arg("--output")
        .arg(&output_path)
        .output()
        .unwrap();
    assert_eq!(refused.status.code(), Some(2));
    assert_eq!(fs::read(&output_path).unwrap(), original);
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn diff_compares_valid_content_and_archives() {
    let directory = temp_dir();
    let changed = directory.join("changed");
    copy_tree(&source(), &changed);
    let manifest_path = changed.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
    manifest["title"] = "A revised title".into();
    fs::write(
        &manifest_path,
        serde_json::to_vec_pretty(&manifest).unwrap(),
    )
    .unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .args(["diff"])
        .arg(source())
        .arg(&changed)
        .args(["--format", "json"])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(result["changes"][0]["path"], "manifest.json/title");
    assert_eq!(result["changes"][0]["after"], "A revised title");
    let archive = directory.join("source.axp");
    axiora_package::pack_directory(&source(), &archive, false).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_axiora"))
        .arg("diff")
        .arg(source())
        .arg(&archive)
        .args(["--format", "json"])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(result["changes"].as_array().unwrap().len(), 0);
    fs::remove_dir_all(directory).unwrap();
}
