use axiora_package::{load_archive, validate_directory};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

fn read_package(path: &Path) -> Result<BTreeMap<String, Value>, String> {
    let mut files = BTreeMap::new();
    if path.is_dir() {
        let validation = validate_directory(path);
        if !validation.valid {
            return Err(format!(
                "{} is invalid: {}",
                path.display(),
                validation
                    .diagnostics
                    .iter()
                    .map(|d| format!("{} {}:{} {}", d.code, d.file, d.path, d.message))
                    .collect::<Vec<_>>()
                    .join("; ")
            ));
        }
        let manifest: Value = serde_json::from_slice(
            &fs::read(path.join("manifest.json")).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        for entry in manifest["files"]
            .as_array()
            .ok_or("missing file inventory")?
        {
            let name = entry["path"].as_str().ok_or("invalid inventory path")?;
            let bytes = fs::read(path.join(name)).map_err(|e| e.to_string())?;
            files.insert(
                name.to_owned(),
                serde_json::from_slice(&bytes).map_err(|e| format!("{name}: {e}"))?,
            );
        }
        files.insert("manifest.json".into(), manifest);
    } else {
        let package = load_archive(path).map_err(|e| e.to_string())?;
        files.insert("manifest.json".into(), package.manifest);
        for (name, bytes) in package.payloads {
            files.insert(
                name.clone(),
                serde_json::from_slice(&bytes).map_err(|e| format!("{name}: {e}"))?,
            );
        }
    }
    Ok(files)
}

fn compare(path: &str, old: &Value, new: &Value, changes: &mut Vec<Value>) {
    if old == new {
        return;
    }
    if let (Some(a), Some(b)) = (old.as_object(), new.as_object()) {
        let keys: BTreeSet<_> = a.keys().chain(b.keys()).collect();
        for key in keys {
            // File inventory hashes and byte counts reflect payload changes already shown elsewhere.
            if path == "manifest.json" && key == "files" {
                continue;
            }
            let child = format!("{path}/{}", key.replace('~', "~0").replace('/', "~1"));
            match (a.get(key), b.get(key)) {
                (Some(x), Some(y)) => compare(&child, x, y, changes),
                (Some(x), None) => changes.push(json!({"path":child,"kind":"removed","before":x})),
                (None, Some(y)) => changes.push(json!({"path":child,"kind":"added","after":y})),
                _ => unreachable!(),
            }
        }
    } else {
        changes.push(json!({"path":path,"kind":"changed","before":old,"after":new}));
    }
}

pub fn run(old: &Path, new: &Path, json_output: bool) -> i32 {
    let (old, new) = match (read_package(old), read_package(new)) {
        (Ok(old), Ok(new)) => (old, new),
        (Err(e), _) | (_, Err(e)) => {
            eprintln!("{e}");
            return 2;
        }
    };
    let mut changes = Vec::new();
    for name in old.keys().chain(new.keys()).collect::<BTreeSet<_>>() {
        match (old.get(name), new.get(name)) {
            (Some(a), Some(b)) => compare(name, a, b, &mut changes),
            (Some(_), None) => changes.push(json!({"path":name,"kind":"removed"})),
            (None, Some(_)) => changes.push(json!({"path":name,"kind":"added"})),
            _ => unreachable!(),
        }
    }
    if json_output {
        println!("{}", json!({"changes": changes}));
    } else if changes.is_empty() {
        println!("No content changes.");
    } else {
        for change in changes {
            println!(
                "{} {}",
                change["kind"].as_str().unwrap(),
                change["path"].as_str().unwrap()
            );
        }
    }
    0
}
