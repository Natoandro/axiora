use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{fs, io::Write, path::Path};

fn encode(v: &Value) -> Vec<u8> {
    let mut b = serde_json::to_vec_pretty(v).unwrap();
    b.push(b'\n');
    b
}
fn id_valid(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 120
        && s.as_bytes()[0].is_ascii_lowercase()
        && !s.ends_with(['.', '-'])
        && !s
            .as_bytes()
            .windows(2)
            .any(|w| matches!(w[0], b'.' | b'-') && matches!(w[1], b'.' | b'-'))
        && s.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-')
}
fn hash_record(path: &str, bytes: &[u8]) -> Value {
    json!({"path":path,"bytes":bytes.len(),"sha256":format!("{:x}",Sha256::digest(bytes))})
}
fn unit(id: &str, title: &str, concept: &str) -> Value {
    json!({
        "schema_version":"0.1.0", "id":id, "version":"0.1.0", "title":title,
        "concept_ids":[concept], "requires_concepts":[],
        "objectives":[{"id":"objective", "concept_id":concept, "skill":"recognition", "description":"Replace with a specific learning objective."}],
        "sources":[{"id":"source", "kind":"original", "citation":"Replace with source attribution.", "locator":"This unit.", "rights":"Rights not yet established; replace before distribution."}],
        "entry":"teach", "activities":{
            "teach":{"type":"exposition", "source_ids":["source"], "content_md":"Replace with teaching content.", "next":"finish"},
            "finish":{"type":"end", "source_ids":["source"], "summary_md":"Replace with a lesson summary. Completion does not establish mastery."}
        }
    })
}
fn create(path: &Path, data: &[u8]) -> Result<(), String> {
    let mut f = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|e| format!("{}: {e}", path.display()))?;
    f.write_all(data).map_err(|e| e.to_string())
}
fn result(f: impl FnOnce() -> Result<(), String>) -> i32 {
    match f() {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("{e}");
            2
        }
    }
}

pub fn run_init(dir: &str, id: &str, title: &str, dry: bool) -> i32 {
    result(|| {
        if title.trim().is_empty() {
            return Err("Title must not be empty".into());
        }
        if !id_valid(id) || id.len() > 112 {
            return Err("Invalid package ID (lowercase letters, digits, dots and hyphens; maximum 112 characters)".into());
        }
        if title.chars().count() > 200 {
            return Err("Title exceeds 200 characters".into());
        }
        let root = Path::new(&dir);
        if root.exists() || fs::symlink_metadata(root).is_ok() {
            return Err(format!("Destination already exists: {dir}"));
        }
        let cid = format!("{id}.concept");
        let uid = format!("{id}.unit");
        let concept = encode(
            &json!({"schema_version":"0.1.0","concepts":[{"id":cid,"title":"Replace with concept title","definition_md":"Replace with a definition.","assumptions":"State relevant assumptions.","relations":[]}]}),
        );
        let content = encode(&unit(&uid, title, &cid));
        let manifest = encode(
            &json!({"schema_version":"0.1.0","package_id":id,"version":"0.1.0","title":title,"language":"en","license":"UNLICENSED","requires_capabilities":["activity.exposition.v1","activity.end.v1"],"conventions":"State relevant conventions.","concept_files":["concepts/core.json"],"units":[{"id":uid,"version":"0.1.0","path":"units/first.json"}],"entry_units":[uid],"review":{"status":"draft","note":"Starter content has not been reviewed."},"files":[hash_record("concepts/core.json",&concept),hash_record("units/first.json",&content)]}),
        );
        if !dry {
            fs::create_dir(root).map_err(|e| format!("{dir}: {e}"))?;
            fs::create_dir(root.join("concepts")).map_err(|e| e.to_string())?;
            fs::create_dir(root.join("units")).map_err(|e| e.to_string())?;
            create(&root.join("concepts/core.json"), &concept)?;
            create(&root.join("units/first.json"), &content)?;
            create(&root.join("manifest.json"), &manifest)?;
        }
        println!(
            "{} package: {dir}",
            if dry { "Would create" } else { "Created" }
        );
        Ok(())
    })
}

pub fn run_add(dir: &str, id: &str, title: &str, concept: &str, dry: bool) -> i32 {
    result(|| {
        if title.trim().is_empty() {
            return Err("Title must not be empty".into());
        }
        if !id_valid(id) || !id_valid(concept) || title.chars().count() > 200 {
            return Err("Invalid ID or title".into());
        }
        let root = Path::new(&dir);
        let check = axiora_package::validate_directory(root);
        if !check.valid {
            return Err("Package must validate before adding a unit; run axiora validate".into());
        }
        let manifest_path = root.join("manifest.json");
        if fs::symlink_metadata(&manifest_path)
            .map_err(|e| e.to_string())?
            .file_type()
            .is_symlink()
        {
            return Err("Refusing symlink manifest".into());
        }
        let original = fs::read(&manifest_path).map_err(|e| e.to_string())?;
        let mut manifest: Value = serde_json::from_slice(&original).map_err(|e| e.to_string())?;
        let exists = manifest["concept_files"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(Value::as_str)
            .any(|p| {
                fs::read(root.join(p))
                    .ok()
                    .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
                    .is_some_and(|v| {
                        v["concepts"]
                            .as_array()
                            .is_some_and(|cs| cs.iter().any(|c| c["id"] == concept))
                    })
            });
        if !exists {
            return Err(format!("Concept not found: {concept}"));
        }
        if manifest["units"]
            .as_array()
            .unwrap()
            .iter()
            .any(|u| u["id"] == id)
        {
            return Err(format!("Unit already exists: {id}"));
        }
        let rel = format!("units/{id}.json");
        let file = root.join(&rel);
        if fs::symlink_metadata(&file).is_ok() {
            return Err(format!("Destination already exists: {}", file.display()));
        }
        let unit_dir = root.join("units");
        if fs::symlink_metadata(&unit_dir).is_ok_and(|m| !m.is_dir() || m.file_type().is_symlink())
        {
            return Err("units must be a real directory".into());
        }
        let content = encode(&unit(id, title, concept));
        manifest["units"]
            .as_array_mut()
            .unwrap()
            .push(json!({"id":id,"version":"0.1.0","path":rel}));
        manifest["entry_units"]
            .as_array_mut()
            .unwrap()
            .push(json!(id));
        manifest["files"]
            .as_array_mut()
            .unwrap()
            .push(hash_record(&rel, &content));
        for cap in ["activity.exposition.v1", "activity.end.v1"] {
            let caps = manifest["requires_capabilities"].as_array_mut().unwrap();
            if !caps.iter().any(|v| v == cap) {
                caps.push(json!(cap));
            }
        }
        if !dry {
            fs::create_dir_all(&unit_dir).map_err(|e| e.to_string())?;
            create(&file, &content)?;
            let temp = root.join(".axiora-manifest.tmp");
            if let Err(e) = create(&temp, &encode(&manifest)) {
                let _ = fs::remove_file(&file);
                return Err(e);
            }
            if fs::read(&manifest_path).ok().as_deref() != Some(&original) {
                let _ = fs::remove_file(&temp);
                let _ = fs::remove_file(&file);
                return Err("Manifest changed during operation".into());
            }
            if let Err(e) = fs::rename(&temp, &manifest_path) {
                let _ = fs::remove_file(&temp);
                let _ = fs::remove_file(&file);
                return Err(e.to_string());
            }
        }
        println!(
            "{} unit: {id} ({rel})",
            if dry { "Would add" } else { "Added" }
        );
        Ok(())
    })
}

pub fn run_schema() -> i32 {
    println!(
        "{}",
        json!({"schema_version":"0.1.0","description":"Discovery summary; validation is implemented in code, not a standalone JSON Schema.","documents":{"manifest":["schema_version","package_id","version","title","language","license","requires_capabilities","conventions","concept_files","units","entry_units","review","files"],"concept":["id","title","definition_md","assumptions","relations"],"unit":["schema_version","id","version","title","concept_ids","requires_concepts","objectives","sources","entry","activities"]},"activity_types":["exposition","choice","proof","reflection","end"],"objective_skills":["recall","recognition","application","proof_comprehension","proof_construction","transfer"],"review_statuses":["draft","mathematically_reviewed","pilot_reviewed"],"authoring":{"init":"axiora init <directory> --id <id> --title <title> [--dry-run]","add_unit":"axiora add unit <directory> --id <id> --title <title> --concept <id> [--dry-run]"}})
    );
    0
}
