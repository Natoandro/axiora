
use super::*;

#[test]
fn reports_utf8_ranges_without_matching_command_prefixes() {
    for (source, span) in [
        ("x α", 2..4),
        (r"x \α", 2..5),
        (r"x \infinity", 2..11),
        (r"x \%", 2..4),
        (r"x \", 2..3),
    ] {
        assert!(
            matches!(parse(source), Err(LayoutError::Unsupported { span: actual }) if actual == span)
        );
    }
}

#[test]
fn rejects_invalid_structure() {
    for source in [
        r"\frac{x}",
        r"\sqrt[3{x}",
        r"\mathbb",
        "{a",
        "a}",
        "x__2",
        "x^1^2",
        r"\left(x",
        r"x\right)",
        r"\big.",
        r"x\limits",
        r"\text{x",
    ] {
        assert!(
            matches!(parse(source), Err(LayoutError::Syntax { .. })),
            "{source}"
        );
    }
}

#[test]
fn limits_input_size_and_recursion() {
    assert!(parse(&"x".repeat(MAX_SOURCE_BYTES)).is_ok());
    assert!(matches!(
        parse(&"x".repeat(MAX_SOURCE_BYTES + 1)),
        Err(LayoutError::LimitExceeded { .. })
    ));
    let nested = format!(
        "{}x{}",
        "{".repeat(MAX_DEPTH + 1),
        "}".repeat(MAX_DEPTH + 1)
    );
    assert!(matches!(
        parse(&nested),
        Err(LayoutError::LimitExceeded { .. })
    ));
}

#[test]
fn preserves_text_spacing_and_handles_prime_with_scripts() {
    let atoms = parse(r"\text{a  b} x''_i^2").unwrap();
    assert!(matches!(&atoms[0].kind, Kind::Text(text) if text == "a  b"));
    assert!(
        matches!(&atoms[1].kind, Kind::Scripts { upper, lower: Some(_), .. } if upper.len() == 3)
    );
}
