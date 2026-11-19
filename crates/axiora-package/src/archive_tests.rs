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
