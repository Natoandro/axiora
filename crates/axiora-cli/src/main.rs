mod diff;
mod refresh;
mod scaffold;

use axiora_package::{inspect_archive, pack_directory, validate_directory};
use clap::{Parser, Subcommand, ValueEnum};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

#[derive(Parser)]
#[command(name = "axiora", version, about = "Author and package Axiora content")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}
#[derive(Clone, Copy, ValueEnum)]
enum Format {
    Text,
    Json,
}
#[derive(Subcommand)]
enum Command {
    /// Create a draft starter package
    Init {
        directory: String,
        #[arg(long)]
        id: String,
        #[arg(long)]
        title: String,
        #[arg(long)]
        dry_run: bool,
    },
    /// Add content to a package
    Add {
        #[command(subcommand)]
        command: AddCommand,
    },
    /// Print a machine-readable schema discovery summary
    Schema,
    /// Print the Markdown authoring guide
    Guide,
    /// Recompute the manifest inventory for valid edited content
    Refresh {
        directory: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
    /// Compare two validated packages or directories
    Diff {
        old: PathBuf,
        new: PathBuf,
        #[arg(long, value_enum, default_value = "text")]
        format: Format,
    },
    /// Validate an unpacked package
    Validate {
        directory: PathBuf,
        #[arg(long, value_enum, default_value = "text")]
        format: Format,
    },
    /// Build an archive from a validated package
    Pack {
        directory: PathBuf,
        #[arg(long)]
        output: PathBuf,
        #[arg(long)]
        overwrite: bool,
    },
    /// Inspect a package archive
    Inspect { archive: PathBuf },
}
#[derive(Subcommand)]
enum AddCommand {
    /// Add a draft exposition unit for an existing concept
    Unit {
        directory: String,
        #[arg(long)]
        id: String,
        #[arg(long)]
        title: String,
        #[arg(long)]
        concept: String,
        #[arg(long)]
        dry_run: bool,
    },
}
fn main() -> ExitCode {
    let cli = Cli::parse();
    let code = match cli.command {
        Command::Init {
            directory,
            id,
            title,
            dry_run,
        } => scaffold::run_init(&directory, &id, &title, dry_run),
        Command::Add {
            command:
                AddCommand::Unit {
                    directory,
                    id,
                    title,
                    concept,
                    dry_run,
                },
        } => scaffold::run_add(&directory, &id, &title, &concept, dry_run),
        Command::Schema => scaffold::run_schema(),
        Command::Guide => {
            print!("{}", include_str!("../AUTHORING.md"));
            0
        }
        Command::Refresh { directory, dry_run } => refresh::run(&directory, dry_run),
        Command::Diff { old, new, format } => diff::run(&old, &new, matches!(format, Format::Json)),
        Command::Validate { directory, format } => run_validate(&directory, format),
        Command::Pack {
            directory,
            output,
            overwrite,
        } => run_pack(&directory, &output, overwrite),
        Command::Inspect { archive } => run_inspect(&archive),
    };
    ExitCode::from(code as u8)
}
fn run_validate(directory: &Path, format: Format) -> i32 {
    let root = normalize(&directory);
    if !root.is_dir() {
        eprintln!("Input directory does not exist: {}", root.display());
        return 2;
    }
    let result = validate_directory(&root);
    if matches!(format, Format::Json) {
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

fn run_pack(source: &Path, output: &Path, overwrite: bool) -> i32 {
    match pack_directory(source, output, overwrite) {
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

fn run_inspect(archive: &Path) -> i32 {
    match inspect_archive(archive) {
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
