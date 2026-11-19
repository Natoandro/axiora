use super::*;
use std::path::PathBuf;

fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../examples/prime-and-maximal")
}

#[test]
fn strict_json_rejects_duplicate_keys_at_nested_depth() {
    let error =
        parse_json_strict(br#"{"outer":{"answer":1,"answer":2}}"#, "input.json").unwrap_err();
    assert_eq!(error.code, "AXC104");
    assert_eq!(error.message, "JSON object contains a duplicate key.");
}

#[test]
fn strict_json_rejects_trailing_data() {
    let error = parse_json_strict(br#"{"ok":true} false"#, "input.json").unwrap_err();
    assert_eq!(error.code, "AXC100");
}

#[test]
fn bounded_json_enforces_package_nesting_limit() {
    let nested = format!("{}0{}", "[".repeat(33), "]".repeat(33));
    let error = parse_bounded_json(nested.as_bytes(), "deep.json").unwrap_err();
    assert_eq!(error.code, "AXC100");
    assert_eq!(error.message, "deep.json exceeds JSON nesting limit.");
}

#[test]
fn kotlin_reference_fixture_validates() {
    let result = validate_directory(&root());
    assert!(result.valid, "{:#?}", result.diagnostics);
    assert!(result.diagnostics.is_empty());
}

#[test]
fn stable_id_and_version_boundaries_match_content_contract() {
    assert!(valid_id("axiora.ca.prime-vs-maximal"));
    assert!(!valid_id("Axiora.bad"));
    assert!(!valid_id("axiora..bad"));
    assert!(valid_revision("0.1.0"));
    assert!(!valid_revision("0.1"));
    assert!(!valid_revision("0.1.0-rc1"));
}
