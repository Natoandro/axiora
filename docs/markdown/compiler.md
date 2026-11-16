# Authoring and compilation

## Start with authored content

The first compiler should validate and package manually authored runtime JSON. It should not start by reading arbitrary PDFs. A reliable hand-authored unit gives the team a reference for what any later generator must produce.

The compiler is a local CLI, sharing contract types and semantic validation with the package archive reader. An Android reader does not exist yet; it is intended to reuse this validator. Python scripts in this repository build and check documentation; they are not the production compiler.

## The content pipeline

| Stage | Input | Output | Responsibility |
| --- | --- | --- | --- |
| Author | Learning objectives, original prose, permitted references | Editable units and concepts | Human author, optionally assisted by AI |
| Parse | UTF-8 JSON files | Typed data or precise syntax errors | Strict parser |
| Validate | Typed units and concepts | Diagnostics with paths and rule IDs | Shared validator |
| Link | Validated units and concept IDs | Resolved graph and capability inventory | Compiler |
| Review | Content and diagnostics | Scoped review record | Human mathematical and teaching reviewers |
| Package | Selected immutable revisions | `.axp` archive and build report | Deterministic packager |

Compilation is independent of packaging. A cache may reuse a unit only if its raw content hash, referenced concept definitions, compiler version, and validation policy are unchanged. The same text under changed assumptions must be revalidated.

## Proposed CLI surface

The Phase 1 and Phase 2 CLI commands are implemented in the local Gradle distribution:

```sh
axiora validate examples/prime-and-maximal --format text
axiora validate examples/prime-and-maximal --format json
axiora inspect build/prime-and-maximal.axp
axiora pack examples/prime-and-maximal --output build/prime-and-maximal.axp
```

`validate` exits 0 for no blocking errors and 1 for invalid content. Invocation or I/O failures exit 2. Human-readable output goes to stderr when a machine-readable JSON result is requested. `pack` refuses a preexisting different output unless an explicit overwrite flag is supplied. Validation never rewrites source files.

An authoring directory may contain a README, but the packager includes only the manifest and its declared payloads. A reader inspecting the final archive must reject undeclared files.

## Diagnostic contract

A diagnostic contains `code`, `severity`, `file`, `path`, `message`, and an optional `suggestion`. JSON paths identify fields precisely; a future YAML parser may add line and column positions.

```json
{
  "code": "AXC203",
  "severity": "error",
  "file": "units/prime-vs-maximal.json",
  "path": "$.activities.predict.transitions.incorrect",
  "message": "Activity target 'investigate' does not exist.",
  "suggestion": "Declare the target or select an existing activity ID."
}
```

Proposed diagnostic groups:

- `AXC1xx`: syntax, schema, unsupported version or capability.
- `AXC2xx`: IDs, references, graph reachability, prerequisites.
- `AXC3xx`: evaluator keys, objectives, evidence or assistance semantics.
- `AXC4xx`: package inventory, paths, byte limits, hashes.
- `AXC5xx`: provenance, review claims, content quality warnings.

Schema errors, invalid references, unsupported capabilities, missing answer keys, and unsafe paths block packaging. Suspected teaching weakness may be a warning during drafting but block a pilot release through its release checklist. The compiler must not label a theorem mathematically false merely because a language model objects to it.

## Validation layers

**Structural:** exact field shapes, unique JSON keys, bounded lengths, enumerated evaluator types, compatible schema version.

**Referential:** local IDs exist, objective concepts are taught by the unit, source references resolve, manifest unit records match their payload identity.

**Control flow:** entry exists, targets exist, all nodes are reachable, activity graph has no cycles, each path terminates, concept prerequisites have no cycles.

**Assessment:** answer keys refer to actual options, exact-choice evidence targets recall or recognition, proof objectives use self-review, and assessment family IDs remain explicit. Validate hints and rubrics as content structures, not as mathematical proofs.

**Packaging:** permitted paths, declared inventory, sizes, raw-byte hashes, capability coverage, and supported content encodings.

**Mathematics and teaching:** human review checks truth, hypotheses, notation, difficulty, useful feedback, fair distractors, and objective alignment. Passing the earlier layers does not imply passing this layer.

## Reproducible packaging

Use lexicographically ordered archive paths, fixed ZIP timestamps, regular-file entries, and UTF-8 JSON with object keys sorted recursively (array order is retained). Hash final payload bytes after serialization, then write the manifest with a sorted payload inventory. For reproducible first-release archives, use ZIP STORE with no compression; compression may be introduced under a pinned toolchain later. The 50 MiB uncompressed limit still applies. The adjacent build report records tool and policy identifiers, package revision, manifest and archive hashes, payload count, and uncompressed byte count; it contains no wall-clock timestamp.

`pack` refuses an existing output unless `--overwrite` is supplied and requires the archive output to be outside the authoring directory. It validates the source first and includes only payloads declared in the manifest inventory. `inspect` checks bounded ZIP metadata and streamed bytes, stages the package temporarily, and runs the shared content validator. It returns a validated revision descriptor; installation remains a later storage responsibility.

## Package container options

The package must be one offline-importable, immutable revision with a manifest, bounded payloads, compatibility checks, and byte-integrity checks. The current contract uses a ZIP container with JSON payload files. This is a selected implementation format, not a product invariant.

| Option | Advantages | Costs and risks | Fit for the current release |
| --- | --- | --- | --- |
| ZIP with separate JSON payload files | One portable file; separately named concepts and units; per-payload sizes and hashes; readers can validate each bounded file; ordinary ZIP tooling can inspect the container. | Requires careful handling of paths, duplicate entries, symlinks, metadata, and expanded sizes. ZIP STORE avoids compression variability but gives no size reduction. | Current choice. It matches the manifest inventory and keeps each runtime payload separately addressable. |
| One JSON package envelope | One portable file; one parser and one bounded document; no archive paths, local headers, or expansion behavior. Deterministic serialization is straightforward. | The reader parses a single document; selective reading is less direct. Payload boundaries and hashes need a canonical representation inside the envelope. | Strong candidate while content remains small and text-only. Simpler than ZIP if independent payload addressing has little practical value. |
| SQLite package | One portable file; generated by the compiler; indexed reads and relational constraints are available without manual SQL authoring. | Makes the database schema and SQLite compatibility part of the package contract. Byte-for-byte reproducibility and validation require a controlled database build and read policy. The reader must treat the database as hostile input and forbid executable database features. | Viable if package size, indexed access, or relational queries justify the database contract. Manual authoring is not a concern because the compiler can create it. |

The current choice rests on a package contract organized around separately named payload files and per-file inventory, bounds, and hashes. It does not rest on JSON being the authoring format. A JSON envelope should be reconsidered if all content must be loaded together and the file boundaries provide little reader or review value. SQLite should be reconsidered if selective access or relational queries become a measured need. ZIP’s security checks remain a real maintenance cost.

A compiler build report lives outside the archive. It records tool version, source hashes, policy version, diagnostics, and the final archive hash. Avoid wall-clock timestamps inside reproducible payloads. A repeated build of unchanged sources with the same compiler and policy must be byte-identical.

## Incremental generation later

When AI authoring is introduced, accept a bounded source scope: selected passages, a concept, or one objective. Produce a draft unit and proposed concept changes. Never silently merge those changes into a shared concept catalog.

Record source edition and locator, extraction quality, model/provider identifier, prompt template version, generation settings, and review outcomes in the authoring workspace. A generated unit remains `draft` until reviewed. Do not put private prompts, textbook scans, or provider credentials in learner packages.

Copyright and licensing must be resolved before publishing source-derived material. Source access is not itself permission to redistribute text or generated derivatives. A future source-ingestion tool should distinguish private authoring workspace files from distributable content.

## Why postpone a YAML DSL?

The essential language is the semantic model: activities, evaluations, transitions, evidence, and provenance. JSON is sufficient to discover whether that model can teach. A second syntax is useful only when repeated authoring work reveals avoidable duplication or difficulty.

Reopen this decision after three substantially different reviewed units: a definition with examples, a theorem with proof analysis, and a proof technique applied in multiple settings. Preserve one normalized runtime contract regardless of authoring syntax.
