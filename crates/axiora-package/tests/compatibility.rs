use axiora_package::{inspect_archive, load_archive, pack_directory, validate_directory};
use serde_json::{Value, json};
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

fn kotlin_archive() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/kotlin-reference/prime-and-maximal.axp")
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
fn reads_kotlin_reference_archive_and_descriptor() {
    let descriptor = inspect_archive(&kotlin_archive()).unwrap();
    assert_eq!(descriptor.package_id, "axiora.ca.first-slice");
    assert_eq!(descriptor.version, "0.1.0");
    assert_eq!(
        descriptor.manifest_sha256,
        "8fdd449a61de27a54467c45eda95b6fc366cc7cbe4ceb0b95c5670342706ebad"
    );
    assert_eq!(
        descriptor.archive_sha256,
        "c75ebf22bb59a3f8a655e3df7fa9dd2e9025d4717a67474e430c7ddf8f53cd7f"
    );
    assert_eq!(descriptor.payload_count, 4);
    assert_eq!(descriptor.uncompressed_bytes, 11_353);
}

#[test]
fn load_returns_owned_validated_payloads() {
    let package = load_archive(&kotlin_archive()).unwrap();
    assert_eq!(package.payloads.len(), 4);
    assert_eq!(
        package.manifest["package_id"].as_str(),
        Some("axiora.ca.first-slice")
    );
    assert!(
        package
            .payloads
            .contains_key("units/proof-and-fresh-check.json")
    );
}

#[test]
fn rust_writer_is_repeatable_and_reads_back() {
    let directory = temp_dir();
    let first = directory.join("first.axp");
    let second = directory.join("second.axp");
    let first_result = pack_directory(&source(), &first, false).unwrap();
    let second_result = pack_directory(&source(), &second, false).unwrap();
    assert_eq!(fs::read(&first).unwrap(), fs::read(&second).unwrap());
    assert_eq!(first_result.descriptor, second_result.descriptor);
    assert_eq!(
        fs::read(&first_result.report_path).unwrap(),
        fs::read(&second_result.report_path).unwrap()
    );
    let report: Value =
        serde_json::from_slice(&fs::read(&first_result.report_path).unwrap()).unwrap();
    assert_eq!(report["uncompressed_bytes"], 13_710);
    assert_eq!(report["payload_count"], 4);
    assert_eq!(inspect_archive(&first).unwrap(), first_result.descriptor);
    assert!(first_result.report_path.is_file());
    fs::remove_dir_all(directory).unwrap();
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
    assert!(String::from_utf8_lossy(&output.stderr).starts_with("Usage: axiora"));
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
fn diagnostic_corpus_matches_kotlin_reference() {
    let expected: Value = serde_json::from_slice(
        &fs::read(
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("tests/fixtures/kotlin-reference/validation-cases.json"),
        )
        .unwrap(),
    )
    .unwrap();
    let cases: [(&str, &str, fn(String) -> String); 9] = [
        ("duplicate_object_key", "manifest.json", |_text| {
            r#"{"schema_version":"0.1.0","schema_version":"0.1.0"}"#.into()
        }),
        ("unsupported_schema", "manifest.json", |text| {
            text.replacen("\"0.1.0\"", "\"0.2.0\"", 1)
        }),
        (
            "broken_activity_reference",
            "units/prime-vs-maximal.json",
            |text| {
                text.replace(
                    "\"incorrect\": \"remediation\"",
                    "\"incorrect\": \"missing-node\"",
                )
            },
        ),
        (
            "unreachable_activity",
            "units/prime-vs-maximal.json",
            |text| {
                text.replace("\"finish\": {", "\"orphan\": {\"type\": \"end\", \"source_ids\": [\"original-exposition\"], \"summary_md\": \"Unused.\"},\n    \"finish\": {")
            },
        ),
        ("activity_cycle", "units/prime-vs-maximal.json", |text| {
            text.replace("\"next\": \"proof\"", "\"next\": \"predict\"")
        }),
        ("missing_capability", "manifest.json", |text| {
            text.replace("    \"activity.end.v1\",\n", "")
        }),
        (
            "evidence_targets_proof_objective",
            "units/prime-vs-maximal.json",
            |text| {
                text.replace(
                    "\"objective_id\": \"distinguish\"",
                    "\"objective_id\": \"prove-implication\"",
                )
            },
        ),
        (
            "proof_targets_recognition_objective",
            "units/prime-vs-maximal.json",
            |text| {
                text.replace(
                    "\"objective_id\": \"prove-implication\"",
                    "\"objective_id\": \"distinguish\"",
                )
            },
        ),
        ("prerequisite_cycle", "concepts/core.json", |text| {
            text.replace("\"relations\": []", "\"relations\": [{\"type\": \"prerequisite\", \"target\": \"axiora.ca.maximal-ideal\"}]")
                .replace("\"type\": \"implies\"", "\"type\": \"prerequisite\"")
        }),
    ];
    let directory = temp_dir();
    for (name, relative, transform) in cases {
        let source_copy = directory.join(name);
        copy_tree(&source(), &source_copy);
        let file = source_copy.join(relative);
        let original = fs::read_to_string(&file).unwrap();
        fs::write(&file, transform(original)).unwrap();
        let actual = validate_directory(&source_copy);
        let actual = json!({
            "valid": actual.valid,
            "diagnostics": actual.diagnostics.iter().map(|diagnostic| {
                let mut value = json!({
                    "code": diagnostic.code,
                    "severity": diagnostic.severity,
                    "file": diagnostic.file,
                    "path": diagnostic.path,
                    "message": diagnostic.message,
                });
                if let Some(suggestion) = &diagnostic.suggestion { value["suggestion"] = json!(suggestion); }
                value
            }).collect::<Vec<_>>(),
        });
        assert_eq!(expected[name]["exit"], 1);
        assert_eq!(expected[name]["stderr"], "");
        assert_eq!(
            actual, expected[name]["stdout"],
            "diagnostic mismatch for {name}"
        );
    }
    fs::remove_dir_all(directory).unwrap();
}
