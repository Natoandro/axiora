# Axiora

An offline mathematics learning environment for developing independent reasoning, with a separate toolchain for authoring and compiling reusable courses.

Axiora combines guided study, examples and counterexamples, proof practice, and deliberate review. Its core works without an account, network connection, or language model. AI may help authors prepare content and may eventually offer optional tutoring; it does not certify mathematical correctness.

**Project status: partial implementation.** Phases 1–4 passed their JVM acceptance gates for content validation, packaging, study, and durable learner records. Phase 5 has an installable [Android study app](android-app/README.md) and an open device acceptance gate. AI integration remains future work. See the [implementation record](IMPLEMENTATION_STATUS.md) for verified scope.

## Read the design

- **Human edition:** [open the HTML handbook](docs/index.html). It is a static, searchable, printable site with no remote assets.
- **AI and source edition:** start at [the Markdown index](docs/markdown/index.md).
- **Implementation sequence:** [phased implementation plan](IMPLEMENTATION_PLAN.md), with tasks and exit criteria.
- **Implementation status:** [verified phase record](IMPLEMENTATION_STATUS.md).
- **Working instructions:** [AGENTS.md](AGENTS.md).
- **Concrete example:** [prime and maximal ideals](examples/prime-and-maximal/README.md), an unpacked illustrative package with a diagnostic question, remediation, proof attempt, and reflection.

The handbook covers the product, pedagogy, architecture, content contract, learner state, compiler, Android experience, delivery plan, and decision log. Requirements describe intended behavior, not shipped capabilities.

## View locally

Open `docs/index.html` directly in a browser, or serve the repository:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Visit <http://127.0.0.1:8000/docs/>. Reading requires no build or installation. HTML, CSS, and search work offline; print from the browser for a paper copy.

## Edit and regenerate

The Markdown under `docs/markdown/` is authoritative. Generated HTML is committed as a convenience. Do not maintain separate prose in HTML.

```sh
uv sync --locked
uv run --locked python scripts/build_docs.py
uv run --locked python scripts/check_docs.py
```

Python tooling is managed by uv through `pyproject.toml` and `uv.lock`, with Python 3.11 or newer. The default `docs` dependency group contains `markdown-it-py`; the checker uses the Python standard library. `uv run` synchronizes the environment automatically, so the initial `uv sync` is optional. Check generated freshness without writing files:

```sh
uv run --locked python scripts/build_docs.py --check
```

The JVM validator and package CLI are built with the committed Gradle wrapper (Java 17 bytecode target; run Gradle with JDK 17 or newer):

```sh
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew test
./gradlew :compiler-cli:installDist
./compiler-cli/build/install/axiora/bin/axiora validate examples/prime-and-maximal
./compiler-cli/build/install/axiora/bin/axiora validate examples/prime-and-maximal --format json
./compiler-cli/build/install/axiora/bin/axiora pack examples/prime-and-maximal --output build/prime-and-maximal.axp
./compiler-cli/build/install/axiora/bin/axiora inspect build/prime-and-maximal.axp
```

`ktlintCheck` checks Kotlin source and Gradle Kotlin scripts across the project. `ktlintFormat` applies ktlint's formatting rules to those files; review the resulting diff before keeping the changes.

Install a local pre-commit hook that checks staged Kotlin files by running this once per clone:

```sh
./gradlew addKtlintCheckGitPreCommitHook
```

Validation and packaging read the unpacked fixture without rewriting it. Packaging writes the `.axp` archive and an adjacent `.report.json`; use `--overwrite` to replace an existing archive. Build and run the Android app separately using the [local demo instructions](android-app/README.md).

`check_docs.py` checks local Markdown/HTML links and fragments, the example's file hashes and documented semantic invariants. It is a documentation check, not a production package validator or mathematical proof checker.

## Rust package CLI

The experimental Rust CLI is a separate crate from the package library used by Android. The Kotlin CLI remains the production implementation during the port. For the Rust CLI, use `cargo run -p axiora-cli --bin axiora --` followed by a command, for example:

```sh
cargo run -p axiora-cli --bin axiora -- init my-course --id example.course --title "My course"
cargo run -p axiora-cli --bin axiora -- add unit my-course --id example.course.second --title "Second lesson" --concept example.course.concept
cargo run -p axiora-cli --bin axiora -- refresh my-course
cargo run -p axiora-cli --bin axiora -- validate my-course --format json
cargo run -p axiora-cli --bin axiora -- pack my-course --output my-course.axp
cargo run -p axiora-cli --bin axiora -- diff my-course my-course.axp --format json
```

`init` creates a draft starter package; `add unit` creates an exposition template for an existing concept. Both accept `--dry-run` and refuse to replace existing destinations. Edit the generated JSON, then run `refresh` to update inventory sizes and hashes after changing payloads; it requires the content to otherwise validate and supports `--dry-run`. `guide` prints an offline Markdown authoring walkthrough; `schema` prints a machine-readable discovery summary, not a complete JSON Schema. `diff` compares validated directories or archives structurally; it ignores manifest inventory hashes and cannot judge mathematical meaning. `inspect` checks an archive. A learner-facing preview and author-supplied lesson tests are not implemented.

## Rust math engine

[Texlet](crates/texlet/Cargo.toml) lays out a small subset of LaTeX math using glyph IDs from a caller-provided font. [Texlet SVG](crates/texlet-svg/Cargo.toml) draws the layout as self-contained SVG paths. See the [notation roadmap](crates/texlet/ROADMAP.md) for supported and planned syntax.

```sh
cargo test --workspace --offline
./scripts/preview_texlet.sh 'x \in \mathbb{Z}' '\mathbb{Q}'
```

Quote each expression separately. The script finds Latin Modern Math through Fontconfig. To use another font or run without Fontconfig, pass `--font /path/to/math-font.otf` before the expressions. The script prints the paths to uniquely named SVGs directly in the gitignored `texlet-previews/` directory. Open the SVGs in a browser. Supply an accessible description when embedding them.

## Proposed first release

One manually authored course slice teaches the distinction between prime and maximal ideals. A learner can import it, work through a branching lesson, write and resume a proof, compare it with a rubric, and return for review, all offline. The first implementation should prove that complete loop before adding automatic textbook ingestion or open-ended AI grading.

The prototype shares Kotlin domain libraries between a native Android client with SQLite and the JVM command-line content compiler.

## Contributions and ownership

Start with the [delivery plan](docs/markdown/delivery.md) and [decision log](docs/markdown/decisions.md). Small, reviewable vertical slices are preferred. Changes to evaluation, progress migration, and package trust need explicit design updates.

No software or content license has been selected. This repository does not grant redistribution rights to third-party textbooks. The example mathematics is written for this project; distribution terms remain an owner decision.
