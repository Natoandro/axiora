use axiora_package::{inspect_archive, pack_directory, validate_directory};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let code = run(std::env::args().skip(1).collect());
    ExitCode::from(code as u8)
}

fn run(args: Vec<String>) -> i32 {
    let Some(command) = args.first().map(String::as_str) else {
        return usage();
    };
    match command {
        "validate" => run_validate(&args[1..]),
        "pack" => run_pack(&args[1..]),
        "inspect" => run_inspect(&args[1..]),
        _ => usage(),
    }
}

fn run_validate(args: &[String]) -> i32 {
    let mut format = "text";
    let mut directory: Option<PathBuf> = None;
    let mut index = 0;
    while index < args.len() {
        if args[index] == "--format" && index + 1 < args.len() {
            index += 1;
            format = &args[index];
        } else if args[index].starts_with("--") || directory.is_some() {
            return usage();
        } else {
            directory = Some(PathBuf::from(&args[index]));
        }
        index += 1;
    }
    let (Some(directory), true) = (directory, matches!(format, "text" | "json")) else {
        return usage();
    };
    let root = normalize(&directory);
    if !root.is_dir() {
        eprintln!("Input directory does not exist: {}", root.display());
        return 2;
    }
    let result = validate_directory(&root);
    if format == "json" {
        print_validation_json(&result.valid, &result.diagnostics);
    } else if result.diagnostics.is_empty() {
        println!("Valid content: {}", root.display());
    } else {
        for diagnostic in &result.diagnostics {
            let suggestion = diagnostic
                .suggestion
                .as_ref()
                .map(|value| format!(" Suggestion: {value}"))
                .unwrap_or_default();
            println!(
                "{} {} {}:{}: {}{}",
                diagnostic.severity.to_uppercase(),
                diagnostic.code,
                diagnostic.file,
                diagnostic.path,
                diagnostic.message,
                suggestion
            );
        }
    }
    if result.valid { 0 } else { 1 }
}

fn run_pack(args: &[String]) -> i32 {
    let mut source: Option<PathBuf> = None;
    let mut output: Option<PathBuf> = None;
    let mut overwrite = false;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--output" if index + 1 < args.len() => {
                index += 1;
                output = Some(PathBuf::from(&args[index]));
            }
            "--overwrite" => overwrite = true,
            flag if flag.starts_with("--") => return usage(),
            _ if source.is_none() => source = Some(PathBuf::from(&args[index])),
            _ => return usage(),
        }
        index += 1;
    }
    let (Some(source), Some(output)) = (source, output) else {
        return usage();
    };
    match pack_directory(&source, &output, overwrite) {
        Ok(result) => {
            println!(
                "Packed {}@{}: {}",
                result.descriptor.package_id,
                result.descriptor.version,
                result.descriptor.archive_sha256
            );
            println!("Build report: {}", result.report_path.display());
            0
        }
        Err(error) => {
            eprintln!("Packaging failed: {error}");
            if error.to_string().starts_with("AXC") {
                1
            } else {
                2
            }
        }
    }
}

fn run_inspect(args: &[String]) -> i32 {
    if args.len() != 1 || args[0].starts_with("--") {
        return usage();
    }
    match inspect_archive(Path::new(&args[0])) {
        Ok(descriptor) => {
            println!(
                "Valid package: {}@{}",
                descriptor.package_id, descriptor.version
            );
            println!("Manifest SHA-256: {}", descriptor.manifest_sha256);
            println!("Archive SHA-256: {}", descriptor.archive_sha256);
            println!(
                "Payload files: {}; uncompressed bytes: {}",
                descriptor.payload_count, descriptor.uncompressed_bytes
            );
            0
        }
        Err(error) => {
            eprintln!("Inspection failed: {error}");
            1
        }
    }
}

fn usage() -> i32 {
    eprintln!(
        "Usage: axiora validate <directory> [--format text|json]\n       axiora pack <directory> --output <file.axp> [--overwrite]\n       axiora inspect <file.axp>"
    );
    2
}

fn normalize(path: &Path) -> PathBuf {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    };
    let mut result = PathBuf::new();
    for component in absolute.components() {
        match component {
            std::path::Component::CurDir => (),
            std::path::Component::ParentDir => {
                result.pop();
            }
            other => result.push(other.as_os_str()),
        }
    }
    result
}

fn print_validation_json(valid: &bool, diagnostics: &[axiora_package::Diagnostic]) {
    let mut output = format!("{{\"valid\":{valid},\"diagnostics\":[");
    for (index, diagnostic) in diagnostics.iter().enumerate() {
        if index > 0 {
            output.push(',');
        }
        output.push_str("{\"code\":");
        append_json_string(&mut output, &diagnostic.code);
        output.push_str(",\"severity\":");
        append_json_string(&mut output, &diagnostic.severity);
        output.push_str(",\"file\":");
        append_json_string(&mut output, &diagnostic.file);
        output.push_str(",\"path\":");
        append_json_string(&mut output, &diagnostic.path);
        output.push_str(",\"message\":");
        append_json_string(&mut output, &diagnostic.message);
        if let Some(suggestion) = &diagnostic.suggestion {
            output.push_str(",\"suggestion\":");
            append_json_string(&mut output, suggestion);
        }
        output.push('}');
    }
    output.push_str("]}\n");
    let _ = io::stdout().write_all(output.as_bytes());
}

fn append_json_string(output: &mut String, text: &str) {
    output.push_str(&serde_json::to_string(text).expect("string serializes"));
}
