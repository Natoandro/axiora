use std::{
    fs,
    path::PathBuf,
    process::Command,
    time::{SystemTime, UNIX_EPOCH},
};
fn tmp() -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "axiora-scaffold-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir(&path).unwrap();
    path
}
fn cli(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_axiora"))
        .args(args)
        .output()
        .unwrap()
}
#[test]
fn scaffold_and_add_produce_valid_content_without_overwrite() {
    let temp = tmp();
    let root = temp.join("course");
    let path = root.to_str().unwrap();
    assert!(
        cli(&[
            "init",
            path,
            "--id",
            "example.course",
            "--title",
            "Example",
            "--dry-run"
        ])
        .status
        .success()
    );
    assert!(!root.exists());
    assert!(
        cli(&["init", path, "--id", "example.course", "--title", "Example"])
            .status
            .success()
    );
    assert!(axiora_package::validate_directory(&root).valid);
    let manifest = fs::read(root.join("manifest.json")).unwrap();
    assert!(
        !cli(&["init", path, "--id", "example.other", "--title", "Other"])
            .status
            .success()
    );
    assert_eq!(manifest, fs::read(root.join("manifest.json")).unwrap());
    assert!(
        cli(&[
            "add",
            "unit",
            path,
            "--id",
            "example.second",
            "--title",
            "Second",
            "--concept",
            "example.course.concept",
            "--dry-run"
        ])
        .status
        .success()
    );
    assert_eq!(manifest, fs::read(root.join("manifest.json")).unwrap());
    assert!(
        cli(&[
            "add",
            "unit",
            path,
            "--id",
            "example.second",
            "--title",
            "Second",
            "--concept",
            "example.course.concept"
        ])
        .status
        .success()
    );
    assert!(axiora_package::validate_directory(&root).valid);
    let updated = fs::read(root.join("manifest.json")).unwrap();
    let manifest_json: serde_json::Value = serde_json::from_slice(&updated).unwrap();
    assert!(
        manifest_json["entry_units"]
            .as_array()
            .unwrap()
            .iter()
            .any(|id| id == "example.second")
    );
    assert!(
        !cli(&[
            "add",
            "unit",
            path,
            "--id",
            "example.second",
            "--title",
            "Second",
            "--concept",
            "example.course.concept"
        ])
        .status
        .success()
    );
    assert_eq!(updated, fs::read(root.join("manifest.json")).unwrap());
    assert!(
        !cli(&[
            "add",
            "unit",
            path,
            "--id",
            "example.third",
            "--title",
            "Third",
            "--concept",
            "missing"
        ])
        .status
        .success()
    );
    fs::remove_dir_all(temp).unwrap();
}
#[test]
fn schema_discovery_is_json_and_rejects_invalid_options() {
    let out = cli(&["schema"]);
    assert!(out.status.success());
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["schema_version"], "0.1.0");
    assert!(
        v["activity_types"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v == "choice")
    );
    assert!(!cli(&["schema", "--bogus"]).status.success());
}

#[test]
fn refresh_updates_edited_content_and_rejects_invalid_inputs_without_writing() {
    let temp = tmp();
    let root = temp.join("course");
    let path = root.to_str().unwrap();
    assert!(
        cli(&["init", path, "--id", "example.course", "--title", "Example"])
            .status
            .success()
    );
    let unit = root.join("units/first.json");
    let content = fs::read_to_string(&unit)
        .unwrap()
        .replace("Replace with teaching content.", "New lesson content.");
    fs::write(&unit, content).unwrap();
    let before = fs::read(root.join("manifest.json")).unwrap();
    assert!(cli(&["refresh", path, "--dry-run"]).status.success());
    assert_eq!(before, fs::read(root.join("manifest.json")).unwrap());
    assert!(cli(&["refresh", path]).status.success());
    assert!(axiora_package::validate_directory(&root).valid);
    let before = fs::read(root.join("manifest.json")).unwrap();
    fs::write(&unit, "not JSON").unwrap();
    assert!(!cli(&["refresh", path]).status.success());
    assert_eq!(before, fs::read(root.join("manifest.json")).unwrap());
    fs::remove_dir_all(temp).unwrap();
}
#[test]
fn refresh_rejects_traversal_and_duplicate_manifest_keys() {
    let temp = tmp();
    let root = temp.join("course");
    let path = root.to_str().unwrap();
    assert!(
        cli(&["init", path, "--id", "example.course", "--title", "Example"])
            .status
            .success()
    );
    let manifest = root.join("manifest.json");
    let original = fs::read_to_string(&manifest).unwrap();
    let unsafe_manifest = original.replace("units/first.json", "../outside.json");
    fs::write(&manifest, &unsafe_manifest).unwrap();
    assert!(!cli(&["refresh", path]).status.success());
    assert_eq!(unsafe_manifest, fs::read_to_string(&manifest).unwrap());
    let duplicate = original.replacen(
        "\"schema_version\":",
        "\"schema_version\":\"0.1.0\",\"schema_version\":",
        1,
    );
    fs::write(&manifest, &duplicate).unwrap();
    assert!(!cli(&["refresh", path]).status.success());
    assert_eq!(duplicate, fs::read_to_string(&manifest).unwrap());
    fs::remove_dir_all(temp).unwrap();
}
#[test]
fn help_is_successful() {
    let out = cli(&["--help"]);
    assert!(out.status.success());
    assert!(String::from_utf8(out.stdout).unwrap().contains("refresh"));
}

#[test]
fn command_help_and_authoring_guide_are_discoverable() {
    let help = cli(&["add", "unit", "--help"]);
    assert!(help.status.success());
    let help = String::from_utf8(help.stdout).unwrap();
    assert!(help.contains("--concept"));
    assert!(help.contains("--dry-run"));
    let guide = cli(&["guide"]);
    assert!(guide.status.success());
    let guide = String::from_utf8(guide.stdout).unwrap();
    assert!(guide.contains("axiora refresh my-course"));
    assert!(guide.contains("concepts/core.json"));
    assert!(guide.contains("not mathematical truth"));
    assert!(!cli(&["guide", "unexpected"]).status.success());
}
